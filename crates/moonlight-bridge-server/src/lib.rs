#![doc = include_str!("../README.md")]

use futures_core::Stream;
use futures_util::StreamExt;
pub use moonlight_bridge_protocol::ErrorCode;
use moonlight_bridge_protocol::{
    COMPRESSION_CODEC_ZSTD, CompressionCodec, CompressionPolicy, DecodedByteBudget,
    DecodedBytePermit, FLAG_HAS_METADATA, Frame, FrameKind, HEADER_LEN, Metadata, MetadataKey,
    MetadataLimits, PeerSettingsV2 as PeerSettings, ReservedMetadataKey, TRACE_CONTEXT_LEN,
    TraceContext,
};
#[cfg(test)]
use moonlight_bridge_protocol::{DEFAULT_MAX_BODY_LEN, DEFAULT_MAX_IN_FLIGHT, SERVER_FEATURES};
use std::{
    cell::RefCell,
    collections::HashMap,
    future::Future,
    io,
    pin::Pin,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::TcpListener,
    sync::{Mutex, Notify, OwnedSemaphorePermit, Semaphore, broadcast, mpsc, oneshot, watch},
    task::AbortHandle,
    time::timeout,
};
use tokio_rustls::{TlsAcceptor, rustls::ServerConfig};
use tracing::Instrument;

mod drain;
pub mod idempotency;
pub use drain::ServerHandle;
mod middleware;
use middleware::CancellationGuard;
pub use middleware::{
    Middleware, MiddlewareFuture, Next, PeerIdentity, RequestCancellation, RequestContext,
};
/// Helpers for loading mutual-TLS server configuration from PEM files.
pub mod tls;

tokio::task_local! {
    static FUNCTION_STAGES: RefCell<Vec<(&'static str, Duration)>>;
}

/// Traces a synchronous backend function as a child stage when tracing is enabled.
pub fn trace_function<T>(name: &'static str, function: impl FnOnce() -> T) -> T {
    if FUNCTION_STAGES.try_with(|_| ()).is_err() {
        return function();
    }
    let started = Instant::now();
    let span = tracing::info_span!(target: "moonlight_bridge::function", "backend.function", function = name);
    let _entered = span.enter();
    let output = function();
    let _ = FUNCTION_STAGES.try_with(|stages| stages.borrow_mut().push((name, started.elapsed())));
    output
}

/// Traces an asynchronous backend function as a child stage when tracing is enabled.
pub async fn trace_async_function<T>(name: &'static str, future: impl Future<Output = T>) -> T {
    if FUNCTION_STAGES.try_with(|_| ()).is_err() {
        return future.await;
    }
    let started = Instant::now();
    let output = future
        .instrument(tracing::info_span!(target: "moonlight_bridge::function", "backend.function", function = name))
        .await;
    let _ = FUNCTION_STAGES.try_with(|stages| stages.borrow_mut().push((name, started.elapsed())));
    output
}

#[cfg(unix)]
use std::path::Path;
#[cfg(unix)]
use tokio::net::UnixListener;

type HandlerFuture = Pin<Box<dyn Future<Output = Result<Vec<u8>, HandlerError>> + Send>>;
pub(crate) type Handler = Arc<dyn Fn(Vec<u8>) -> HandlerFuture + Send + Sync>;
/// Type-erased asynchronous sequence returned by a server-streaming handler.
pub type ServerStream<T> = Pin<Box<dyn Stream<Item = Result<T, HandlerError>> + Send + 'static>>;
/// Maps successful stream items while preserving handler failures.
pub fn map_server_stream<T, U, F>(stream: ServerStream<T>, mut mapper: F) -> ServerStream<U>
where
    T: 'static,
    U: 'static,
    F: FnMut(T) -> U + Send + 'static,
{
    Box::pin(stream.map(move |item| item.map(&mut mapper)))
}
/// Fallibly maps successful stream items while preserving existing handler failures.
pub fn try_map_server_stream<T, U, F>(stream: ServerStream<T>, mut mapper: F) -> ServerStream<U>
where
    T: 'static,
    U: 'static,
    F: FnMut(T) -> Result<U, HandlerError> + Send + 'static,
{
    Box::pin(stream.map(move |item| item.and_then(&mut mapper)))
}
/// Creates a server stream from a finite iterator.
pub fn iter_server_stream<T, I>(items: I) -> ServerStream<T>
where
    T: 'static,
    I: IntoIterator<Item = Result<T, HandlerError>>,
    I::IntoIter: Send + 'static,
{
    Box::pin(futures_util::stream::iter(items))
}
type RawServerStream = ServerStream<Vec<u8>>;
type StreamHandlerFuture =
    Pin<Box<dyn Future<Output = Result<RawServerStream, HandlerError>> + Send>>;
type StreamHandler = Arc<dyn Fn(Vec<u8>) -> StreamHandlerFuture + Send + Sync>;

struct ActiveRequest {
    task: AbortHandle,
    credits: Option<Arc<Semaphore>>,
}

struct ConnectionResources {
    limits: ServerLimits,
    request_bytes: Arc<Semaphore>,
    _admission: OwnedSemaphorePermit,
}

struct ConnectionConfig {
    server: PeerSettings,
    hello_timeout: Duration,
    events: EventHub,
}

struct StreamDelivery {
    credits: Arc<Semaphore>,
    responses: mpsc::Sender<Frame>,
}

type ActiveRequests = Arc<Mutex<HashMap<u64, ActiveRequest>>>;

#[derive(Clone)]
/// Bounded broadcast channel for one-way events sent to connected clients.
pub struct EventHub {
    sender: broadcast::Sender<Frame>,
}

impl EventHub {
    /// Creates a hub with the specified per-receiver backlog capacity.
    ///
    /// # Panics
    /// Panics when `capacity` is zero.
    pub fn new(capacity: usize) -> Self {
        assert!(capacity > 0, "event broadcast capacity must be positive");
        let (sender, _) = broadcast::channel(capacity);
        Self { sender }
    }

    /// Publishes an event to every connected client. Event IDs share the generated u32 ID space.
    pub fn publish(&self, event_id: u32, body: Vec<u8>) -> usize {
        assert_ne!(event_id, 0, "MoonLightBridge event ID must not be zero");
        self.sender
            .send(Frame::new(FrameKind::Event, event_id, 0, body))
            .unwrap_or(0)
    }
}

impl Default for EventHub {
    fn default() -> Self {
        Self::new(1_024)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
/// Point-in-time health and load information for a server.
pub struct ServerHealth {
    /// Whether the accept loop has started and the server is ready.
    pub ready: bool,
    /// Number of currently open client connections.
    pub active_connections: u64,
    /// Number of handlers currently executing across all connections.
    pub active_requests: u64,
    /// Configured concurrent-request limit for one connection.
    pub max_in_flight_per_connection: u32,
    /// Time elapsed since server construction.
    pub uptime: Duration,
}

#[derive(Clone)]
/// Cheap cloneable handle for reading server health while the server is running.
pub struct HealthHandle(Arc<RuntimeState>);

impl HealthHandle {
    /// Reads a lock-free snapshot of the current server state.
    pub fn snapshot(&self) -> ServerHealth {
        ServerHealth {
            ready: self.0.ready.load(Ordering::Relaxed),
            active_connections: self.0.active_connections.load(Ordering::Relaxed),
            active_requests: self.0.active_requests.load(Ordering::Relaxed),
            max_in_flight_per_connection: self.0.max_in_flight,
            uptime: self.0.started.elapsed(),
        }
    }
}

struct RuntimeState {
    ready: AtomicBool,
    active_connections: AtomicU64,
    active_requests: AtomicU64,
    max_in_flight: u32,
    compression_policy: CompressionPolicy,
    decoded_byte_budget: DecodedByteBudget,
    draining: AtomicBool,
    drain_tx: watch::Sender<bool>,
    force_drain_tx: watch::Sender<bool>,
    state_changed: Notify,
    started: Instant,
}

struct ConnectionGuard(Arc<RuntimeState>);

impl Drop for ConnectionGuard {
    fn drop(&mut self) {
        self.0.active_connections.fetch_sub(1, Ordering::Relaxed);
        self.0.state_changed.notify_waiters();
    }
}

struct RequestGuard(Arc<RuntimeState>);

impl Drop for RequestGuard {
    fn drop(&mut self) {
        self.0.active_requests.fetch_sub(1, Ordering::Relaxed);
        self.0.state_changed.notify_waiters();
    }
}

#[derive(Debug, Clone, Copy)]
/// Request metadata supplied to a [`Telemetry`] implementation.
pub struct RequestInfo {
    /// Generated method identifier.
    pub method_id: u32,
    /// Generated service and method name, when registered by generated code.
    pub method_name: Option<&'static str>,
    /// Connection-local correlation identifier.
    pub request_id: u64,
    /// Decoded application payload size in bytes.
    pub request_bytes: usize,
    /// Propagated trace identifiers, when provided by the client.
    pub trace_context: Option<TraceContext>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
/// Terminal result reported to a request observation.
pub enum RequestOutcome {
    /// Handler returned a successful response.
    Success {
        /// Encoded response size in bytes.
        response_bytes: usize,
    },
    /// Handler or runtime returned a transport-level error.
    Error {
        /// Stable protocol error classification.
        code: ErrorCode,
    },
}

/// Per-request observation created by a [`Telemetry`] implementation.
pub trait RequestObservation: Send {
    /// Records a named generated or user-instrumented backend stage.
    fn record_stage(&mut self, _name: &'static str, _duration: Duration) {}
    /// Completes this observation with the request's terminal outcome.
    fn finish(self: Box<Self>, outcome: RequestOutcome);
}

/// Pluggable request instrumentation invoked outside application handlers.
pub trait Telemetry: Send + Sync {
    /// Starts an observation, or returns `None` to skip this request.
    fn start_request(&self, info: RequestInfo) -> Option<Box<dyn RequestObservation>>;
}

#[derive(Default)]
/// Fan-out adapter that forwards observations to multiple telemetry delegates.
pub struct TelemetryChain(Vec<Arc<dyn Telemetry>>);

impl TelemetryChain {
    /// Creates a chain in delegate iteration order.
    pub fn new(delegates: impl IntoIterator<Item = Arc<dyn Telemetry>>) -> Self {
        Self(delegates.into_iter().collect())
    }
}

impl Telemetry for TelemetryChain {
    fn start_request(&self, info: RequestInfo) -> Option<Box<dyn RequestObservation>> {
        let observations: Vec<_> = self
            .0
            .iter()
            .filter_map(|telemetry| telemetry.start_request(info))
            .collect();
        if observations.is_empty() {
            None
        } else {
            Some(Box::new(ChainedObservation(observations)))
        }
    }
}

struct ChainedObservation(Vec<Box<dyn RequestObservation>>);

impl RequestObservation for ChainedObservation {
    fn record_stage(&mut self, name: &'static str, duration: Duration) {
        for observation in &mut self.0 {
            observation.record_stage(name, duration);
        }
    }

    fn finish(self: Box<Self>, outcome: RequestOutcome) {
        for observation in self.0 {
            observation.finish(outcome);
        }
    }
}

#[derive(Clone, Default)]
/// Lock-free aggregate request counters implementing [`Telemetry`].
pub struct MoonLightMetrics(Arc<MetricsInner>);

#[derive(Default)]
struct MetricsInner {
    started: AtomicU64,
    succeeded: AtomicU64,
    failed: AtomicU64,
    request_bytes: AtomicU64,
    response_bytes: AtomicU64,
    total_latency_nanos: AtomicU64,
    max_latency_nanos: AtomicU64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
/// Cumulative counters returned by [`MoonLightMetrics::snapshot`].
pub struct MetricsSnapshot {
    /// Requests for which an observation was started.
    pub started: u64,
    /// Successfully completed requests.
    pub succeeded: u64,
    /// Failed, cancelled, or abandoned requests.
    pub failed: u64,
    /// Total decoded request payload bytes.
    pub request_bytes: u64,
    /// Total successful response payload bytes.
    pub response_bytes: u64,
    /// Sum of observed request latency in nanoseconds.
    pub total_latency_nanos: u64,
    /// Largest observed request latency in nanoseconds.
    pub max_latency_nanos: u64,
}

impl MoonLightMetrics {
    /// Reads all counters without blocking request processing.
    pub fn snapshot(&self) -> MetricsSnapshot {
        MetricsSnapshot {
            started: self.0.started.load(Ordering::Relaxed),
            succeeded: self.0.succeeded.load(Ordering::Relaxed),
            failed: self.0.failed.load(Ordering::Relaxed),
            request_bytes: self.0.request_bytes.load(Ordering::Relaxed),
            response_bytes: self.0.response_bytes.load(Ordering::Relaxed),
            total_latency_nanos: self.0.total_latency_nanos.load(Ordering::Relaxed),
            max_latency_nanos: self.0.max_latency_nanos.load(Ordering::Relaxed),
        }
    }
}

impl Telemetry for MoonLightMetrics {
    fn start_request(&self, info: RequestInfo) -> Option<Box<dyn RequestObservation>> {
        self.0.started.fetch_add(1, Ordering::Relaxed);
        self.0
            .request_bytes
            .fetch_add(info.request_bytes as u64, Ordering::Relaxed);
        Some(Box::new(MetricsObservation {
            metrics: self.clone(),
            started: Instant::now(),
            finished: false,
        }))
    }
}

struct MetricsObservation {
    metrics: MoonLightMetrics,
    started: Instant,
    finished: bool,
}

impl RequestObservation for MetricsObservation {
    fn finish(mut self: Box<Self>, outcome: RequestOutcome) {
        self.finished = true;
        self.record(outcome);
    }
}

impl MetricsObservation {
    fn record(&self, outcome: RequestOutcome) {
        let elapsed = self.started.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64;
        self.metrics
            .0
            .total_latency_nanos
            .fetch_add(elapsed, Ordering::Relaxed);
        self.metrics
            .0
            .max_latency_nanos
            .fetch_max(elapsed, Ordering::Relaxed);
        match outcome {
            RequestOutcome::Success { response_bytes } => {
                self.metrics.0.succeeded.fetch_add(1, Ordering::Relaxed);
                self.metrics
                    .0
                    .response_bytes
                    .fetch_add(response_bytes as u64, Ordering::Relaxed);
            }
            RequestOutcome::Error { .. } => {
                self.metrics.0.failed.fetch_add(1, Ordering::Relaxed);
            }
        }
    }
}

impl Drop for MetricsObservation {
    fn drop(&mut self) {
        if !self.finished {
            self.record(RequestOutcome::Error {
                code: ErrorCode::Cancelled,
            });
        }
    }
}

#[derive(Debug)]
/// Handler failure converted into a structured ERROR frame.
pub struct HandlerError {
    /// Stable transport error classification.
    pub code: ErrorCode,
    /// Human-readable diagnostic message, not a stable application contract.
    pub message: String,
}

impl HandlerError {
    /// Creates a handler error with an explicit wire code.
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    /// Creates an [`ErrorCode::Internal`] handler error.
    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Internal, message)
    }
}

#[derive(Clone, Default)]
/// Immutable method router consumed by a [`Server`].
pub struct Router {
    handlers: Arc<HashMap<u32, Handler>>,
    stream_handlers: Arc<HashMap<u32, StreamHandler>>,
    method_names: Arc<HashMap<u32, &'static str>>,
    required_scopes: Arc<HashMap<u32, &'static [&'static str]>>,
    middleware: Arc<[Arc<dyn Middleware>]>,
    telemetry: Option<Arc<dyn Telemetry>>,
}

/// Builder that registers handlers and request telemetry.
pub struct RouterBuilder {
    handlers: HashMap<u32, Handler>,
    stream_handlers: HashMap<u32, StreamHandler>,
    method_names: HashMap<u32, &'static str>,
    required_scopes: HashMap<u32, &'static [&'static str]>,
    middleware: Vec<Arc<dyn Middleware>>,
    telemetry: Option<Arc<dyn Telemetry>>,
}

#[derive(Clone, Copy)]
struct RequestCompression<'a> {
    codecs: u32,
    policy: CompressionPolicy,
    budget: &'a DecodedByteBudget,
}

impl Router {
    /// Starts an empty router builder.
    pub fn builder() -> RouterBuilder {
        RouterBuilder {
            handlers: HashMap::new(),
            stream_handlers: HashMap::new(),
            method_names: HashMap::new(),
            required_scopes: HashMap::new(),
            middleware: Vec::new(),
            telemetry: None,
        }
    }

    #[cfg(test)]
    async fn dispatch(
        &self,
        frame: Frame,
        max_response_body_len: u32,
        max_metadata_len: u32,
        compression: RequestCompression<'_>,
    ) -> Frame {
        self.dispatch_with_peer(
            frame,
            max_response_body_len,
            max_metadata_len,
            compression,
            None,
        )
        .await
    }

    async fn dispatch_with_peer(
        &self,
        mut frame: Frame,
        max_response_body_len: u32,
        max_metadata_len: u32,
        compression: RequestCompression<'_>,
        peer_identity: Option<PeerIdentity>,
    ) -> Frame {
        let ParsedRequest {
            deadline,
            trace_context,
            metadata,
            payload,
            _decoded_permit,
        } = match request_payload(&mut frame, max_metadata_len, compression) {
            Ok(parts) => parts,
            Err((code, message)) => return error_frame(&frame, code, message),
        };
        let mut observation = self.telemetry.as_ref().and_then(|telemetry| {
            telemetry.start_request(RequestInfo {
                method_id: frame.method_id,
                method_name: self.method_names.get(&frame.method_id).copied(),
                request_id: frame.request_id,
                request_bytes: payload.len(),
                trace_context,
            })
        });
        let Some(handler) = self.handlers.get(&frame.method_id) else {
            tracing::debug!(
                method_id = frame.method_id,
                request_id = frame.request_id,
                "MoonLightBridge request used an unknown method"
            );
            finish_observation(
                observation,
                RequestOutcome::Error {
                    code: ErrorCode::UnknownMethod,
                },
            );
            return error_frame(&frame, ErrorCode::UnknownMethod, "method is not registered");
        };
        let absolute_deadline = deadline.map(|duration| tokio::time::Instant::now() + duration);
        let cancellation = RequestCancellation::new();
        let context = RequestContext::new(
            frame.method_id,
            frame.request_id,
            absolute_deadline,
            metadata,
            self.required_scopes
                .get(&frame.method_id)
                .copied()
                .unwrap_or(&[]),
            peer_identity,
            cancellation.clone(),
        );
        let handler = handler.clone();
        let middleware = self.middleware.clone();
        let collect_stages = observation.is_some();
        let instrumented = async move {
            let invocation = async move {
                let mut cancellation_guard = CancellationGuard::new(cancellation);
                let result = if middleware.is_empty() {
                    handler(payload).await
                } else {
                    Next::root(middleware, handler).run(context, payload).await
                };
                cancellation_guard.complete();
                result
            };
            if collect_stages {
                FUNCTION_STAGES
                    .scope(RefCell::new(Vec::new()), async move {
                        let result = invocation.await;
                        let stages = FUNCTION_STAGES
                            .with(|stages| std::mem::take(&mut *stages.borrow_mut()));
                        (result, stages)
                    })
                    .await
            } else {
                (invocation.await, Vec::new())
            }
        };
        let (result, stages) = match deadline {
            Some(duration) => match timeout(duration, instrumented).await {
                Ok(result) => result,
                Err(_) => {
                    tracing::debug!(
                        method_id = frame.method_id,
                        request_id = frame.request_id,
                        "MoonLightBridge request exceeded its deadline"
                    );
                    finish_observation(
                        observation,
                        RequestOutcome::Error {
                            code: ErrorCode::DeadlineExceeded,
                        },
                    );
                    return error_frame(
                        &frame,
                        ErrorCode::DeadlineExceeded,
                        "request deadline exceeded",
                    );
                }
            },
            None => instrumented.await,
        };
        if let Some(observation) = observation.as_mut() {
            for (name, duration) in stages {
                observation.record_stage(name, duration);
            }
        }

        match result {
            Ok(body) => {
                if body.len() > max_response_body_len as usize {
                    tracing::warn!(
                        method_id = frame.method_id,
                        request_id = frame.request_id,
                        response_bytes = body.len(),
                        max_body_len = max_response_body_len,
                        "MoonLightBridge handler response exceeds the negotiated body limit"
                    );
                    finish_observation(
                        observation,
                        RequestOutcome::Error {
                            code: ErrorCode::ResourceExhausted,
                        },
                    );
                    return error_frame(
                        &frame,
                        ErrorCode::ResourceExhausted,
                        "response exceeds negotiated body limit",
                    );
                }
                tracing::trace!(
                    method_id = frame.method_id,
                    request_id = frame.request_id,
                    response_bytes = body.len(),
                    "MoonLightBridge request completed"
                );
                finish_observation(
                    observation,
                    RequestOutcome::Success {
                        response_bytes: body.len(),
                    },
                );
                Frame::new(FrameKind::Response, frame.method_id, frame.request_id, body)
            }
            Err(error) => {
                if error.code == ErrorCode::Internal {
                    tracing::error!(
                        method_id = frame.method_id,
                        request_id = frame.request_id,
                        error_code = ?error.code,
                        "MoonLightBridge handler returned an internal error"
                    );
                } else {
                    tracing::debug!(
                        method_id = frame.method_id,
                        request_id = frame.request_id,
                        error_code = ?error.code,
                        "MoonLightBridge request failed"
                    );
                }
                finish_observation(observation, RequestOutcome::Error { code: error.code });
                error_frame(&frame, error.code, &error.message)
            }
        }
    }

    fn is_streaming(&self, method_id: u32) -> bool {
        self.stream_handlers.contains_key(&method_id)
    }

    async fn dispatch_stream(
        &self,
        mut frame: Frame,
        max_response_body_len: u32,
        max_metadata_len: u32,
        compression: RequestCompression<'_>,
        delivery: StreamDelivery,
        peer_identity: Option<PeerIdentity>,
    ) {
        let StreamDelivery { credits, responses } = delivery;
        let ParsedRequest {
            deadline,
            trace_context,
            metadata,
            payload,
            _decoded_permit,
        } = match request_payload(&mut frame, max_metadata_len, compression) {
            Ok(parts) => parts,
            Err((code, message)) => {
                let _ = responses.send(error_frame(&frame, code, message)).await;
                return;
            }
        };
        let observation = self.telemetry.as_ref().and_then(|telemetry| {
            telemetry.start_request(RequestInfo {
                method_id: frame.method_id,
                method_name: self.method_names.get(&frame.method_id).copied(),
                request_id: frame.request_id,
                request_bytes: payload.len(),
                trace_context,
            })
        });
        let Some(handler) = self.stream_handlers.get(&frame.method_id).cloned() else {
            finish_observation(
                observation,
                RequestOutcome::Error {
                    code: ErrorCode::UnknownMethod,
                },
            );
            let _ = responses
                .send(error_frame(
                    &frame,
                    ErrorCode::UnknownMethod,
                    "method is not registered",
                ))
                .await;
            return;
        };
        let cancellation = RequestCancellation::new();
        let context = RequestContext::new(
            frame.method_id,
            frame.request_id,
            deadline.map(|duration| tokio::time::Instant::now() + duration),
            metadata,
            self.required_scopes
                .get(&frame.method_id)
                .copied()
                .unwrap_or(&[]),
            peer_identity,
            cancellation.clone(),
        );
        let payload = if self.middleware.is_empty() {
            payload
        } else {
            let identity: Handler = Arc::new(|body| Box::pin(async move { Ok(body) }));
            match Next::root(self.middleware.clone(), identity)
                .run(context, payload)
                .await
            {
                Ok(payload) => payload,
                Err(error) => {
                    finish_observation(observation, RequestOutcome::Error { code: error.code });
                    let _ = responses
                        .send(error_frame(&frame, error.code, &error.message))
                        .await;
                    return;
                }
            }
        };
        let mut cancellation_guard = CancellationGuard::new(cancellation);
        async {
            let expires = deadline.map(|duration| tokio::time::Instant::now() + duration);
            let open = handler(payload);
            let opened = match expires {
                Some(at) => match tokio::time::timeout_at(at, open).await {
                    Ok(result) => result,
                    Err(_) => {
                        finish_observation(
                            observation,
                            RequestOutcome::Error {
                                code: ErrorCode::DeadlineExceeded,
                            },
                        );
                        let _ = responses
                            .send(error_frame(
                                &frame,
                                ErrorCode::DeadlineExceeded,
                                "request deadline exceeded",
                            ))
                            .await;
                        return;
                    }
                },
                None => open.await,
            };
            let mut stream = match opened {
                Ok(stream) => stream,
                Err(error) => {
                    finish_observation(observation, RequestOutcome::Error { code: error.code });
                    let _ = responses
                        .send(error_frame(&frame, error.code, &error.message))
                        .await;
                    return;
                }
            };
            let mut response_bytes = 0usize;
            loop {
                let item = match expires {
                    Some(at) => match tokio::time::timeout_at(at, stream.next()).await {
                        Ok(item) => item,
                        Err(_) => {
                            finish_observation(
                                observation,
                                RequestOutcome::Error {
                                    code: ErrorCode::DeadlineExceeded,
                                },
                            );
                            let _ = responses
                                .send(error_frame(
                                    &frame,
                                    ErrorCode::DeadlineExceeded,
                                    "request deadline exceeded",
                                ))
                                .await;
                            return;
                        }
                    },
                    None => stream.next().await,
                };
                match item {
                    Some(Ok(body)) if body.len() <= max_response_body_len as usize => {
                        let credit = credits.acquire();
                        let permit = match expires {
                            Some(at) => match tokio::time::timeout_at(at, credit).await {
                                Ok(Ok(permit)) => permit,
                                Ok(Err(_)) => return,
                                Err(_) => {
                                    finish_observation(
                                        observation,
                                        RequestOutcome::Error {
                                            code: ErrorCode::DeadlineExceeded,
                                        },
                                    );
                                    let _ = responses
                                        .send(error_frame(
                                            &frame,
                                            ErrorCode::DeadlineExceeded,
                                            "request deadline exceeded",
                                        ))
                                        .await;
                                    return;
                                }
                            },
                            None => match credit.await {
                                Ok(permit) => permit,
                                Err(_) => return,
                            },
                        };
                        permit.forget();
                        response_bytes += body.len();
                        if responses
                            .send(Frame::new(
                                FrameKind::StreamItem,
                                frame.method_id,
                                frame.request_id,
                                body,
                            ))
                            .await
                            .is_err()
                        {
                            return;
                        }
                    }
                    Some(Ok(_)) => {
                        finish_observation(
                            observation,
                            RequestOutcome::Error {
                                code: ErrorCode::ResourceExhausted,
                            },
                        );
                        let _ = responses
                            .send(error_frame(
                                &frame,
                                ErrorCode::ResourceExhausted,
                                "stream item exceeds negotiated body limit",
                            ))
                            .await;
                        return;
                    }
                    Some(Err(error)) => {
                        finish_observation(observation, RequestOutcome::Error { code: error.code });
                        let _ = responses
                            .send(error_frame(&frame, error.code, &error.message))
                            .await;
                        return;
                    }
                    None => {
                        finish_observation(observation, RequestOutcome::Success { response_bytes });
                        let _ = responses
                            .send(Frame::new(
                                FrameKind::StreamEnd,
                                frame.method_id,
                                frame.request_id,
                                Vec::new(),
                            ))
                            .await;
                        return;
                    }
                }
            }
        }
        .await;
        cancellation_guard.complete();
    }
}

impl RouterBuilder {
    /// Associates a generated display name with an already registered method.
    pub fn method_name(mut self, method_id: u32, name: &'static str) -> Self {
        assert!(
            self.handlers.contains_key(&method_id) || self.stream_handlers.contains_key(&method_id),
            "cannot name an unregistered MoonLightBridge method: 0x{method_id:08X}"
        );
        assert!(
            !name.is_empty(),
            "MoonLightBridge method name must not be empty"
        );
        let previous = self.method_names.insert(method_id, name);
        assert!(
            previous.is_none(),
            "duplicate MoonLightBridge method name registered: 0x{method_id:08X}"
        );
        self
    }

    /// Appends a middleware layer. Layers wrap handlers in registration order.
    pub fn layer(mut self, middleware: Arc<dyn Middleware>) -> Self {
        self.middleware.push(middleware);
        self
    }

    /// Installs the telemetry implementation used for handled requests.
    pub fn telemetry(mut self, telemetry: Arc<dyn Telemetry>) -> Self {
        self.telemetry = Some(telemetry);
        self
    }

    /// Registers an asynchronous byte-level handler for a method ID.
    ///
    /// Generated registration functions should normally be preferred.
    ///
    /// # Panics
    /// Panics when the method ID has already been registered.
    pub fn route<F, Fut>(mut self, method_id: u32, handler: F) -> Self
    where
        F: Fn(Vec<u8>) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = Result<Vec<u8>, HandlerError>> + Send + 'static,
    {
        assert!(
            !self.stream_handlers.contains_key(&method_id),
            "duplicate MoonLightBridge method ID registered: 0x{method_id:08X}"
        );
        let previous = self
            .handlers
            .insert(method_id, Arc::new(move |body| Box::pin(handler(body))));
        assert!(
            previous.is_none(),
            "duplicate MoonLightBridge method ID registered: 0x{method_id:08X}"
        );
        self
    }

    /// Registers a handler and exposes generated authorization scopes to middleware.
    pub fn route_scoped<F, Fut>(
        mut self,
        method_id: u32,
        required_scopes: &'static [&'static str],
        handler: F,
    ) -> Self
    where
        F: Fn(Vec<u8>) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = Result<Vec<u8>, HandlerError>> + Send + 'static,
    {
        assert!(
            !self.stream_handlers.contains_key(&method_id),
            "duplicate MoonLightBridge method ID registered: 0x{method_id:08X}"
        );
        let previous = self
            .handlers
            .insert(method_id, Arc::new(move |body| Box::pin(handler(body))));
        assert!(
            previous.is_none(),
            "duplicate MoonLightBridge method ID registered: 0x{method_id:08X}"
        );
        self.required_scopes.insert(method_id, required_scopes);
        self
    }

    /// Registers a credit-controlled server-streaming byte-level handler.
    ///
    /// Generated registration functions should normally be preferred. At most one item is polled
    /// ahead; delivery always waits for client credit.
    ///
    /// # Panics
    /// Panics when the method ID has already been registered as unary or streaming.
    pub fn route_stream<F, Fut, S>(mut self, method_id: u32, handler: F) -> Self
    where
        F: Fn(Vec<u8>) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = Result<S, HandlerError>> + Send + 'static,
        S: Stream<Item = Result<Vec<u8>, HandlerError>> + Send + 'static,
    {
        assert!(
            !self.handlers.contains_key(&method_id)
                && !self.stream_handlers.contains_key(&method_id),
            "duplicate MoonLightBridge method ID registered: 0x{method_id:08X}"
        );
        self.stream_handlers.insert(
            method_id,
            Arc::new(move |body| {
                let future = handler(body);
                Box::pin(async move {
                    let stream = future.await?;
                    Ok(Box::pin(stream) as RawServerStream)
                })
            }),
        );
        self
    }

    /// Registers a server-streaming handler and exposes generated scopes to middleware.
    pub fn route_stream_scoped<F, Fut, S>(
        mut self,
        method_id: u32,
        required_scopes: &'static [&'static str],
        handler: F,
    ) -> Self
    where
        F: Fn(Vec<u8>) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = Result<S, HandlerError>> + Send + 'static,
        S: Stream<Item = Result<Vec<u8>, HandlerError>> + Send + 'static,
    {
        assert!(
            !self.handlers.contains_key(&method_id)
                && !self.stream_handlers.contains_key(&method_id),
            "duplicate MoonLightBridge method ID registered: 0x{method_id:08X}"
        );
        self.stream_handlers.insert(
            method_id,
            Arc::new(move |body| {
                let future = handler(body);
                Box::pin(async move {
                    let stream = future.await?;
                    Ok(Box::pin(stream) as RawServerStream)
                })
            }),
        );
        self.required_scopes.insert(method_id, required_scopes);
        self
    }

    /// Freezes handler registration into a cloneable router.
    pub fn build(self) -> Router {
        Router {
            handlers: Arc::new(self.handlers),
            stream_handlers: Arc::new(self.stream_handlers),
            method_names: Arc::new(self.method_names),
            required_scopes: Arc::new(self.required_scopes),
            middleware: self.middleware.into(),
            telemetry: self.telemetry,
        }
    }
}

fn finish_observation(observation: Option<Box<dyn RequestObservation>>, outcome: RequestOutcome) {
    if let Some(observation) = observation {
        observation.finish(outcome);
    }
}

/// Tokio server accepting MoonLightBridge connections over one configured transport.
pub struct Server {
    listener: Listener,
    router: Router,
    settings: PeerSettings,
    hello_timeout: Duration,
    events: EventHub,
    runtime: Arc<RuntimeState>,
    limits: ServerLimits,
}

#[derive(Debug, Clone, Copy)]
/// Process-wide admission and I/O limits applied by a [`Server`].
pub struct ServerLimits {
    /// Maximum number of accepted connections, including TLS handshakes.
    pub max_connections: usize,
    /// Maximum bytes retained by decoded request frames across all connections.
    pub max_buffered_request_bytes: usize,
    /// Maximum time allowed to receive one complete frame.
    pub frame_read_timeout: Duration,
    /// Maximum time spent draining the response writer during connection shutdown.
    pub writer_shutdown_timeout: Duration,
}

impl Default for ServerLimits {
    fn default() -> Self {
        Self {
            max_connections: 1_024,
            max_buffered_request_bytes: 64 * 1024 * 1024,
            frame_read_timeout: Duration::from_secs(30),
            writer_shutdown_timeout: Duration::from_millis(500),
        }
    }
}

enum Listener {
    Tcp(TcpListener),
    Tls(TcpListener, TlsAcceptor),
    #[cfg(unix)]
    Unix(UnixListener),
}

impl Server {
    fn runtime(settings: PeerSettings) -> (EventHub, Arc<RuntimeState>) {
        let (drain_tx, _) = watch::channel(false);
        let (force_drain_tx, _) = watch::channel(false);
        (
            EventHub::default(),
            Arc::new(RuntimeState {
                ready: AtomicBool::new(false),
                active_connections: AtomicU64::new(0),
                active_requests: AtomicU64::new(0),
                max_in_flight: settings.max_in_flight,
                compression_policy: CompressionPolicy {
                    max_decoded_body_len: settings.max_decoded_body_len,
                    ..CompressionPolicy::default()
                },
                decoded_byte_budget: DecodedByteBudget::new(64 * 1024 * 1024)
                    .expect("non-zero decoded byte budget"),
                draining: AtomicBool::new(false),
                drain_tx,
                force_drain_tx,
                state_changed: Notify::new(),
                started: Instant::now(),
            }),
        )
    }

    /// Alias for [`Server::bind_tcp`].
    pub async fn bind(address: &str, router: Router) -> io::Result<Self> {
        Self::bind_tcp(address, router).await
    }

    /// Binds a plaintext TCP listener without starting the accept loop.
    pub async fn bind_tcp(address: &str, router: Router) -> io::Result<Self> {
        let settings = PeerSettings::default();
        let (events, runtime) = Self::runtime(settings);
        Ok(Self {
            listener: Listener::Tcp(TcpListener::bind(address).await?),
            router,
            settings,
            hello_timeout: Duration::from_secs(10),
            events,
            runtime,
            limits: ServerLimits::default(),
        })
    }

    /// Binds a TCP listener and performs TLS for every accepted connection.
    pub async fn bind_tls(
        address: &str,
        router: Router,
        config: Arc<ServerConfig>,
    ) -> io::Result<Self> {
        let settings = PeerSettings::default();
        let (events, runtime) = Self::runtime(settings);
        Ok(Self {
            listener: Listener::Tls(TcpListener::bind(address).await?, TlsAcceptor::from(config)),
            router,
            settings,
            hello_timeout: Duration::from_secs(10),
            events,
            runtime,
            limits: ServerLimits::default(),
        })
    }

    #[cfg(unix)]
    /// Binds a Unix-domain socket without starting the accept loop.
    pub fn bind_unix(path: impl AsRef<Path>, router: Router) -> io::Result<Self> {
        let settings = PeerSettings::default();
        let (events, runtime) = Self::runtime(settings);
        Ok(Self {
            listener: Listener::Unix(UnixListener::bind(path)?),
            router,
            settings,
            hello_timeout: Duration::from_secs(10),
            events,
            runtime,
            limits: ServerLimits::default(),
        })
    }

    /// Maximum time accepted connections may remain silent before sending HELLO.
    pub fn with_hello_timeout(mut self, hello_timeout: Duration) -> Self {
        assert!(
            !hello_timeout.is_zero(),
            "HELLO timeout must be greater than zero"
        );
        self.hello_timeout = hello_timeout;
        self
    }

    /// Replaces the process-wide connection, buffering, and I/O limits.
    ///
    /// # Panics
    /// Panics when a count is zero, the request budget cannot hold one maximum-size frame,
    /// or an I/O timeout is zero.
    pub fn with_limits(mut self, limits: ServerLimits) -> Self {
        assert!(
            limits.max_connections > 0 && limits.max_connections <= Semaphore::MAX_PERMITS,
            "maximum connections must fit the Tokio semaphore"
        );
        assert!(
            limits.max_buffered_request_bytes >= self.settings.max_body_len as usize,
            "request byte budget must hold one maximum-size frame"
        );
        assert!(
            limits.max_buffered_request_bytes <= Semaphore::MAX_PERMITS,
            "request byte budget must fit the Tokio semaphore"
        );
        assert!(
            !limits.frame_read_timeout.is_zero() && !limits.writer_shutdown_timeout.is_zero(),
            "server I/O timeouts must be positive"
        );
        self.limits = limits;
        self
    }

    /// Returns the event hub associated with this server.
    pub fn events(&self) -> EventHub {
        self.events.clone()
    }

    /// Installs a pre-created hub so application handlers can publish events.
    pub fn with_event_hub(mut self, events: EventHub) -> Self {
        self.events = events;
        self
    }

    /// Returns a handle that remains usable after [`Server::run`] takes ownership.
    pub fn health(&self) -> HealthHandle {
        HealthHandle(self.runtime.clone())
    }

    /// Returns a handle that can stop admission and await graceful shutdown.
    pub fn handle(&self) -> ServerHandle {
        ServerHandle::new(self.runtime.clone())
    }

    /// Runs the accept loop until an unrecoverable listener error occurs.
    pub async fn run(self) -> io::Result<()> {
        if self.runtime.draining.load(Ordering::Acquire) {
            return Ok(());
        }
        self.runtime.ready.store(true, Ordering::Release);
        let connection_permits = Arc::new(Semaphore::new(self.limits.max_connections));
        let request_bytes = Arc::new(Semaphore::new(self.limits.max_buffered_request_bytes));
        let mut drain_rx = self.runtime.drain_tx.subscribe();
        match self.listener {
            Listener::Tcp(listener) => loop {
                let (stream, _) = tokio::select! {
                    accepted = listener.accept() => accepted?,
                    changed = drain_rx.changed() => {
                        if changed.is_err() || *drain_rx.borrow() { return Ok(()); }
                        continue;
                    }
                };
                stream.set_nodelay(true)?;
                let router = self.router.clone();
                let settings = self.settings;
                let hello_timeout = self.hello_timeout;
                let events = self.events.clone();
                let runtime = self.runtime.clone();
                let limits = self.limits;
                let Ok(connection_permit) = connection_permits.clone().try_acquire_owned() else {
                    tracing::warn!("MoonLightBridge connection limit reached");
                    continue;
                };
                let request_bytes = request_bytes.clone();
                tokio::spawn(async move {
                    if let Err(error) = serve_connection(
                        stream,
                        router,
                        None,
                        ConnectionConfig {
                            server: settings,
                            hello_timeout,
                            events,
                        },
                        runtime,
                        ConnectionResources {
                            limits,
                            request_bytes,
                            _admission: connection_permit,
                        },
                    )
                    .await
                    {
                        tracing::debug!(%error, "MoonLightBridge TCP connection closed");
                    }
                });
            },
            Listener::Tls(listener, acceptor) => loop {
                let (stream, _) = tokio::select! {
                    accepted = listener.accept() => accepted?,
                    changed = drain_rx.changed() => {
                        if changed.is_err() || *drain_rx.borrow() { return Ok(()); }
                        continue;
                    }
                };
                stream.set_nodelay(true)?;
                let acceptor = acceptor.clone();
                let router = self.router.clone();
                let settings = self.settings;
                let hello_timeout = self.hello_timeout;
                let events = self.events.clone();
                let runtime = self.runtime.clone();
                let limits = self.limits;
                let Ok(connection_permit) = connection_permits.clone().try_acquire_owned() else {
                    tracing::warn!("MoonLightBridge connection limit reached");
                    continue;
                };
                let request_bytes = request_bytes.clone();
                tokio::spawn(async move {
                    let handshake = timeout(Duration::from_secs(10), acceptor.accept(stream)).await;
                    match handshake {
                        Ok(Ok(stream)) => {
                            let peer_identity = stream
                                .get_ref()
                                .1
                                .peer_certificates()
                                .and_then(|certificates| certificates.first())
                                .map(|certificate| {
                                    PeerIdentity::tls_certificate(certificate.as_ref().to_vec())
                                });
                            if let Err(error) = serve_connection(
                                stream,
                                router,
                                peer_identity,
                                ConnectionConfig {
                                    server: settings,
                                    hello_timeout,
                                    events,
                                },
                                runtime,
                                ConnectionResources {
                                    limits,
                                    request_bytes,
                                    _admission: connection_permit,
                                },
                            )
                            .await
                            {
                                tracing::debug!(%error, "MoonLightBridge TLS connection closed");
                            }
                        }
                        Ok(Err(error)) => {
                            tracing::warn!(%error, "MoonLightBridge TLS handshake rejected")
                        }
                        Err(_) => tracing::warn!("MoonLightBridge TLS handshake timed out"),
                    }
                });
            },
            #[cfg(unix)]
            Listener::Unix(listener) => loop {
                let (stream, _) = tokio::select! {
                    accepted = listener.accept() => accepted?,
                    changed = drain_rx.changed() => {
                        if changed.is_err() || *drain_rx.borrow() { return Ok(()); }
                        continue;
                    }
                };
                let router = self.router.clone();
                let settings = self.settings;
                let hello_timeout = self.hello_timeout;
                let events = self.events.clone();
                let runtime = self.runtime.clone();
                let limits = self.limits;
                let Ok(connection_permit) = connection_permits.clone().try_acquire_owned() else {
                    tracing::warn!("MoonLightBridge connection limit reached");
                    continue;
                };
                let request_bytes = request_bytes.clone();
                tokio::spawn(async move {
                    if let Err(error) = serve_connection(
                        stream,
                        router,
                        None,
                        ConnectionConfig {
                            server: settings,
                            hello_timeout,
                            events,
                        },
                        runtime,
                        ConnectionResources {
                            limits,
                            request_bytes,
                            _admission: connection_permit,
                        },
                    )
                    .await
                    {
                        tracing::debug!(%error, "MoonLightBridge Unix connection closed");
                    }
                });
            },
        }
    }
}

async fn serve_connection<S>(
    mut stream: S,
    router: Router,
    peer_identity: Option<PeerIdentity>,
    config: ConnectionConfig,
    runtime: Arc<RuntimeState>,
    resources: ConnectionResources,
) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let ConnectionConfig {
        server,
        hello_timeout,
        events,
    } = config;
    let ConnectionResources {
        limits,
        request_bytes,
        _admission,
    } = resources;
    runtime.active_connections.fetch_add(1, Ordering::Relaxed);
    let _connection_guard = ConnectionGuard(runtime.clone());
    let hello = timeout(
        hello_timeout,
        read_frame(&mut stream, PeerSettings::BODY_LEN as u32),
    )
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "MoonLightBridge HELLO timed out"))??;
    if hello.kind != FrameKind::Hello || hello.request_id != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "HELLO must be the first frame",
        ));
    }
    let client = PeerSettings::decode(&hello.body)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    let negotiated = PeerSettings {
        max_body_len: client.max_body_len.min(server.max_body_len),
        max_decoded_body_len: client.max_decoded_body_len.min(server.max_decoded_body_len),
        max_metadata_len: client.max_metadata_len.min(server.max_metadata_len),
        max_in_flight: client.max_in_flight.min(server.max_in_flight),
        max_concurrent_streams: client
            .max_concurrent_streams
            .min(server.max_concurrent_streams),
        initial_stream_credit: client
            .initial_stream_credit
            .min(server.initial_stream_credit),
        compression_codecs: client.compression_codecs & server.compression_codecs,
        features: client.features & server.features,
        diagnostic_features: client.diagnostic_features & server.diagnostic_features,
    };
    write_frame(
        &mut stream,
        &Frame::new(FrameKind::Welcome, 0, 0, negotiated.encode()),
    )
    .await?;
    tracing::debug!(
        max_body_len = negotiated.max_body_len,
        max_decoded_body_len = negotiated.max_decoded_body_len,
        max_metadata_len = negotiated.max_metadata_len,
        max_in_flight = negotiated.max_in_flight,
        max_concurrent_streams = negotiated.max_concurrent_streams,
        initial_stream_credit = negotiated.initial_stream_credit,
        compression_codecs = negotiated.compression_codecs,
        features = negotiated.features,
        "MoonLightBridge handshake completed"
    );

    let (mut reader, mut writer) = tokio::io::split(stream);
    let (responses_tx, mut responses_rx) =
        mpsc::channel::<Frame>(negotiated.max_in_flight as usize);
    let (frames_tx, mut frames_rx) =
        mpsc::channel::<io::Result<BufferedFrame>>(negotiated.max_in_flight as usize);
    let active: ActiveRequests = Arc::new(Mutex::new(HashMap::new()));
    let permits = Arc::new(Semaphore::new(negotiated.max_in_flight as usize));
    let mut event_rx = events.sender.subscribe();
    let mut drain_rx = runtime.drain_tx.subscribe();
    let mut force_drain_rx = runtime.force_drain_tx.subscribe();
    let active_changed = Arc::new(Notify::new());
    let mut draining = *drain_rx.borrow();
    let connection_compression_policy = CompressionPolicy {
        max_decoded_body_len: negotiated.max_decoded_body_len,
        ..runtime.compression_policy
    };

    // Keep exactly one read future alive for the connection. Selecting directly on
    // read_frame alongside events is not cancellation-safe: an event can drop a
    // partially completed read and make the next call start in the middle of a frame.
    let reader_task = tokio::spawn(async move {
        loop {
            let result = read_frame_buffered(
                &mut reader,
                negotiated.max_body_len,
                limits.frame_read_timeout,
                request_bytes.clone(),
            )
            .await;
            let terminal = result.is_err();
            if frames_tx.send(result).await.is_err() || terminal {
                break;
            }
        }
    });

    let mut writer_task = tokio::spawn(async move {
        const MAX_BATCH_FRAMES: usize = 64;
        const MAX_BATCH_BYTES: usize = 512 * 1024;
        let mut encoded = Vec::with_capacity(MAX_BATCH_BYTES);
        let mut deferred = None;
        loop {
            let first = match deferred.take() {
                Some(frame) => frame,
                None => match responses_rx.recv().await {
                    Some(frame) => frame,
                    None => break,
                },
            };
            let first = fit_outbound_frame(first, negotiated.max_decoded_body_len);
            let first = compress_outbound_frame(
                first,
                negotiated.compression_codecs,
                connection_compression_policy,
                negotiated.max_metadata_len,
            );
            let first = fit_outbound_frame(first, negotiated.max_body_len);
            encoded.clear();
            first
                .encode_into(&mut encoded)
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
            let mut frames = 1;
            while frames < MAX_BATCH_FRAMES {
                let Ok(frame) = responses_rx.try_recv() else {
                    break;
                };
                let frame = fit_outbound_frame(frame, negotiated.max_decoded_body_len);
                let frame = compress_outbound_frame(
                    frame,
                    negotiated.compression_codecs,
                    connection_compression_policy,
                    negotiated.max_metadata_len,
                );
                let frame = fit_outbound_frame(frame, negotiated.max_body_len);
                let frame_len = HEADER_LEN + frame.body.len();
                if encoded.len() + frame_len > MAX_BATCH_BYTES {
                    deferred = Some(frame);
                    break;
                }
                frame
                    .encode_into(&mut encoded)
                    .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
                frames += 1;
            }
            writer.write_all(&encoded).await?;
        }
        Ok::<(), io::Error>(())
    });

    if draining {
        responses_tx
            .send(Frame::new(FrameKind::GoAway, 0, 0, Vec::new()))
            .await
            .map_err(channel_closed)?;
    }
    let connection_result = loop {
        let buffered = tokio::select! {
            result = frames_rx.recv() => match result {
                Some(Ok(frame)) => frame,
                Some(Err(error)) if error.kind() == io::ErrorKind::UnexpectedEof => break Ok(()),
                Some(Err(error)) => break Err(error),
                None => break Err(io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    "request reader stopped unexpectedly",
                )),
            },
            event = event_rx.recv(), if negotiated.features & moonlight_bridge_protocol::FEATURE_SERVER_EVENTS != 0 => {
                match event {
                    Ok(frame) => {
                        if frame.body.len() > negotiated.max_decoded_body_len as usize {
                            tracing::warn!(
                                event_id = frame.method_id,
                                event_bytes = frame.body.len(),
                                "MoonLightBridge event exceeds the negotiated body limit"
                            );
                            continue;
                        }
                        if let Err(error) = responses_tx.send(frame).await {
                            break Err(channel_closed(error));
                        }
                        continue;
                    }
                    Err(broadcast::error::RecvError::Lagged(skipped)) => {
                        tracing::warn!(skipped, "MoonLightBridge client event stream lagged");
                        continue;
                    }
                    Err(broadcast::error::RecvError::Closed) => continue,
                }
            },
            changed = drain_rx.changed(), if !draining => {
                if changed.is_ok() && *drain_rx.borrow() {
                    draining = true;
                    if responses_tx.send(Frame::new(FrameKind::GoAway, 0, 0, Vec::new())).await.is_err() {
                        break Err(io::Error::new(io::ErrorKind::BrokenPipe, "response writer stopped"));
                    }
                    if active.lock().await.is_empty() {
                        let _ = responses_tx.send(Frame::new(FrameKind::Goodbye, 0, 0, Vec::new())).await;
                        break Ok(());
                    }
                }
                continue;
            },
            _ = active_changed.notified(), if draining => {
                if active.lock().await.is_empty() {
                    let _ = responses_tx.send(Frame::new(FrameKind::Goodbye, 0, 0, Vec::new())).await;
                    break Ok(());
                }
                continue;
            },
            changed = force_drain_rx.changed() => {
                if changed.is_err() || *force_drain_rx.borrow() {
                    let _ = responses_tx.send(Frame::new(FrameKind::Goodbye, 0, 0, Vec::new())).await;
                    break Ok(());
                }
                continue;
            },
        };
        let BufferedFrame {
            frame,
            permit: body_permit,
        } = buffered;
        match frame.kind {
            FrameKind::Request => {
                if draining {
                    if responses_tx
                        .send(error_frame(
                            &frame,
                            ErrorCode::Unavailable,
                            "server connection is draining",
                        ))
                        .await
                        .is_err()
                    {
                        break Err(io::Error::new(
                            io::ErrorKind::BrokenPipe,
                            "response writer stopped",
                        ));
                    }
                    continue;
                }
                if frame.request_id == 0 {
                    break Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "REQUEST request_id must not be zero",
                    ));
                }
                let permit = match permits.clone().try_acquire_owned() {
                    Ok(permit) => permit,
                    Err(_) => {
                        if let Err(error) = responses_tx
                            .send(error_frame(
                                &frame,
                                ErrorCode::ResourceExhausted,
                                "too many active requests",
                            ))
                            .await
                        {
                            break Err(channel_closed(error));
                        }
                        continue;
                    }
                };
                let router = router.clone();
                let responses_tx = responses_tx.clone();
                let active_for_task = active.clone();
                let active_changed_for_task = active_changed.clone();
                let request_id = frame.request_id;
                let runtime_for_task = runtime.clone();
                let peer_identity = peer_identity.clone();
                let max_decoded_body_len = negotiated.max_decoded_body_len;
                let streaming = router.is_streaming(frame.method_id);
                if streaming
                    && negotiated.features & moonlight_bridge_protocol::FEATURE_SERVER_STREAMING
                        == 0
                {
                    if responses_tx
                        .send(error_frame(
                            &frame,
                            ErrorCode::InvalidRequest,
                            "server streaming was not negotiated",
                        ))
                        .await
                        .is_err()
                    {
                        break Err(io::Error::new(
                            io::ErrorKind::BrokenPipe,
                            "response writer stopped",
                        ));
                    }
                    continue;
                }
                let stream_credits = streaming.then(|| Arc::new(Semaphore::new(0)));
                let task_credits = stream_credits.clone();
                let (start_tx, start_rx) = oneshot::channel();
                let task = tokio::spawn(async move {
                    let _permit = permit;
                    let _body_permit = body_permit;
                    runtime_for_task
                        .active_requests
                        .fetch_add(1, Ordering::Relaxed);
                    let _request_guard = RequestGuard(runtime_for_task.clone());
                    if start_rx.await.is_err() {
                        return;
                    }
                    if let Some(credits) = task_credits {
                        router
                            .dispatch_stream(
                                frame,
                                max_decoded_body_len,
                                negotiated.max_metadata_len,
                                RequestCompression {
                                    codecs: negotiated.compression_codecs,
                                    policy: connection_compression_policy,
                                    budget: &runtime_for_task.decoded_byte_budget,
                                },
                                StreamDelivery {
                                    credits,
                                    responses: responses_tx.clone(),
                                },
                                peer_identity,
                            )
                            .await;
                    } else {
                        let response = fit_outbound_frame(
                            router
                                .dispatch_with_peer(
                                    frame,
                                    max_decoded_body_len,
                                    negotiated.max_metadata_len,
                                    RequestCompression {
                                        codecs: negotiated.compression_codecs,
                                        policy: connection_compression_policy,
                                        budget: &runtime_for_task.decoded_byte_budget,
                                    },
                                    peer_identity,
                                )
                                .await,
                            max_decoded_body_len,
                        );
                        let _ = responses_tx.send(response).await;
                    }
                    active_for_task.lock().await.remove(&request_id);
                    active_changed_for_task.notify_one();
                });
                let mut active_requests = active.lock().await;
                if active_requests.contains_key(&request_id) {
                    drop(active_requests);
                    task.abort();
                    break Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        format!("duplicate active request_id: {request_id}"),
                    ));
                }
                active_requests.insert(
                    request_id,
                    ActiveRequest {
                        task: task.abort_handle(),
                        credits: stream_credits,
                    },
                );
                drop(active_requests);
                let _ = start_tx.send(());
            }
            FrameKind::Cancel => {
                if let Some(request) = active.lock().await.remove(&frame.request_id) {
                    request.task.abort();
                    active_changed.notify_one();
                }
            }
            FrameKind::StreamCredit => {
                if frame.method_id != 0 || frame.request_id == 0 || frame.body.len() != 8 {
                    break Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "invalid STREAM_CREDIT frame",
                    ));
                }
                let count = u64::from_be_bytes(frame.body.as_slice().try_into().unwrap());
                if count == 0 {
                    break Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "STREAM_CREDIT must be positive",
                    ));
                }
                let active_requests = active.lock().await;
                let Some(request) = active_requests.get(&frame.request_id) else {
                    continue;
                };
                let Some(credits) = request.credits.as_ref() else {
                    break Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "credits target a unary request",
                    ));
                };
                let available = credits.available_permits();
                let room = Semaphore::MAX_PERMITS.saturating_sub(available);
                credits.add_permits(usize::try_from(count).unwrap_or(usize::MAX).min(room));
            }
            FrameKind::Ping => {
                if frame.request_id == 0 {
                    break Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "PING request_id must not be zero",
                    ));
                }
                if let Err(error) = responses_tx
                    .send(Frame::new(FrameKind::Pong, 0, frame.request_id, frame.body))
                    .await
                {
                    break Err(channel_closed(error));
                }
            }
            FrameKind::Health => {
                if frame.request_id == 0 || frame.method_id != 0 || !frame.body.is_empty() {
                    break Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "invalid HEALTH frame",
                    ));
                }
                let health = HealthHandle(runtime.clone()).snapshot();
                let mut body = Vec::with_capacity(29);
                body.push(moonlight_bridge_protocol::VERSION);
                body.push(u8::from(health.ready));
                body.extend_from_slice(&[0, 0]);
                body.extend_from_slice(&health.active_connections.to_be_bytes());
                body.extend_from_slice(&health.active_requests.to_be_bytes());
                body.extend_from_slice(&health.max_in_flight_per_connection.to_be_bytes());
                body.extend_from_slice(
                    &(health.uptime.as_millis().min(u128::from(u64::MAX)) as u64).to_be_bytes(),
                );
                if let Err(error) = responses_tx
                    .send(Frame::new(
                        FrameKind::HealthStatus,
                        0,
                        frame.request_id,
                        body,
                    ))
                    .await
                {
                    break Err(channel_closed(error));
                }
            }
            FrameKind::Goodbye => break Ok(()),
            _ => {
                break Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "unexpected client frame kind",
                ));
            }
        }
    };

    // This epilogue deliberately runs for EOF, protocol errors and I/O errors alike.
    for (_, request) in active.lock().await.drain() {
        request.task.abort();
    }
    reader_task.abort();
    let _ = reader_task.await;
    drop(responses_tx);
    let writer_result = match timeout(limits.writer_shutdown_timeout, &mut writer_task).await {
        Ok(result) => result.map_err(io::Error::other)?,
        Err(_) => {
            writer_task.abort();
            let _ = writer_task.await;
            Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "response writer shutdown timed out",
            ))
        }
    };
    connection_result.and(writer_result)
}

struct ParsedRequest {
    deadline: Option<Duration>,
    trace_context: Option<TraceContext>,
    metadata: Metadata,
    payload: Vec<u8>,
    _decoded_permit: Option<DecodedBytePermit>,
}

fn request_payload(
    frame: &mut Frame,
    max_metadata_len: u32,
    compression: RequestCompression<'_>,
) -> Result<ParsedRequest, (ErrorCode, &'static str)> {
    let (metadata, payload_offset) = if frame.flags & FLAG_HAS_METADATA != 0 {
        let limits = MetadataLimits {
            max_bytes: max_metadata_len,
            ..MetadataLimits::default()
        };
        let (metadata, payload) = Metadata::decode(&frame.body, limits)
            .map_err(|_| (ErrorCode::InvalidRequest, "invalid request metadata"))?;
        (metadata, frame.body.len() - payload.len())
    } else {
        (Metadata::new(), 0)
    };
    let deadline = if let Some(value) =
        metadata.get(&MetadataKey::Reserved(ReservedMetadataKey::DeadlineMillis))
    {
        let bytes: [u8; 4] = value.try_into().map_err(|_| {
            (
                ErrorCode::InvalidRequest,
                "deadline metadata must contain four bytes",
            )
        })?;
        let millis = u32::from_be_bytes(bytes);
        if millis == 0 {
            return Err((
                ErrorCode::InvalidRequest,
                "deadline must be greater than zero",
            ));
        }
        Some(Duration::from_millis(millis as u64))
    } else {
        None
    };
    let trace_context = if let Some(value) =
        metadata.get(&MetadataKey::Reserved(ReservedMetadataKey::TraceContext))
    {
        if value.len() != TRACE_CONTEXT_LEN {
            return Err((
                ErrorCode::InvalidRequest,
                "trace-context metadata must contain 25 bytes",
            ));
        }
        let context = TraceContext::decode(value)
            .map_err(|_| (ErrorCode::InvalidRequest, "invalid trace context"))?;
        Some(context)
    } else {
        None
    };
    let payload_length = frame.body.len() - payload_offset;
    frame.body.copy_within(payload_offset.., 0);
    frame.body.truncate(payload_length);
    let encoded_payload = std::mem::take(&mut frame.body);
    let codec_value = metadata.get(&MetadataKey::Reserved(
        ReservedMetadataKey::CompressionCodec,
    ));
    let original_value = metadata.get(&MetadataKey::Reserved(ReservedMetadataKey::OriginalLength));
    let (payload, decoded_permit) = match (codec_value, original_value) {
        (None, None) => (encoded_payload, None),
        (Some(&[codec]), Some(original)) => {
            let codec = CompressionCodec::try_from(codec).map_err(|_| {
                (
                    ErrorCode::CompressionFailure,
                    "unsupported compression codec",
                )
            })?;
            if codec != CompressionCodec::Zstd || compression.codecs & COMPRESSION_CODEC_ZSTD == 0 {
                return Err((
                    ErrorCode::CompressionFailure,
                    "compression codec was not negotiated",
                ));
            }
            let original: [u8; 4] = original.try_into().map_err(|_| {
                (
                    ErrorCode::CompressionFailure,
                    "invalid original payload length",
                )
            })?;
            compression
                .policy
                .decode_with_reservation(
                    codec,
                    &encoded_payload,
                    u32::from_be_bytes(original),
                    compression.budget,
                )
                .map_err(|_| (ErrorCode::CompressionFailure, "compressed payload rejected"))?
        }
        _ => {
            return Err((
                ErrorCode::CompressionFailure,
                "incomplete compression metadata",
            ));
        }
    };
    Ok(ParsedRequest {
        deadline,
        trace_context,
        metadata,
        payload,
        _decoded_permit: decoded_permit,
    })
}

fn error_frame(request: &Frame, code: ErrorCode, message: &str) -> Frame {
    Frame::new(
        FrameKind::Error,
        request.method_id,
        request.request_id,
        code.encode(message),
    )
}

fn fit_outbound_frame(mut frame: Frame, max_body_len: u32) -> Frame {
    if frame.body.len() <= max_body_len as usize {
        return frame;
    }
    tracing::warn!(
        method_id = frame.method_id,
        request_id = frame.request_id,
        response_bytes = frame.body.len(),
        max_body_len,
        "MoonLightBridge response exceeds the negotiated body limit"
    );
    frame.kind = FrameKind::Error;
    frame.flags = 0;
    frame.body = if max_body_len >= 2 {
        ErrorCode::ResourceExhausted.encode("")
    } else {
        Vec::new()
    };
    frame
}

fn compress_outbound_frame(
    mut frame: Frame,
    compression_codecs: u32,
    policy: CompressionPolicy,
    max_metadata_len: u32,
) -> Frame {
    if compression_codecs & COMPRESSION_CODEC_ZSTD == 0
        || !matches!(
            frame.kind,
            FrameKind::Response | FrameKind::Event | FrameKind::StreamItem
        )
    {
        return frame;
    }
    let (mut metadata, payload_offset) = if frame.flags & FLAG_HAS_METADATA != 0 {
        let limits = MetadataLimits {
            max_bytes: max_metadata_len,
            ..MetadataLimits::default()
        };
        match Metadata::decode(&frame.body, limits) {
            Ok((metadata, payload)) => (metadata, frame.body.len() - payload.len()),
            Err(_) => return frame,
        }
    } else {
        (Metadata::new(), 0)
    };
    let payload = frame.body.split_off(payload_offset);
    let compressed = match policy.encode(payload, CompressionCodec::Zstd) {
        Ok(compressed) => compressed,
        Err(_) => {
            frame.kind = FrameKind::Error;
            frame.flags = 0;
            frame.body = ErrorCode::CompressionFailure.encode("response compression failed");
            return frame;
        }
    };
    if compressed.codec == CompressionCodec::Zstd
        && metadata
            .insert_reserved(
                ReservedMetadataKey::CompressionCodec,
                vec![compressed.codec as u8],
            )
            .and_then(|()| {
                metadata.insert_reserved(
                    ReservedMetadataKey::OriginalLength,
                    compressed.original_len.to_be_bytes().to_vec(),
                )
            })
            .is_err()
    {
        frame.kind = FrameKind::Error;
        frame.flags = 0;
        frame.body = ErrorCode::CompressionFailure.encode("response metadata conflict");
        return frame;
    }
    let mut body = Vec::new();
    if !metadata.is_empty() {
        if metadata.encode_prefix(&mut body).is_err()
            || body.len().saturating_sub(4) > max_metadata_len as usize
        {
            frame.kind = FrameKind::Error;
            frame.flags = 0;
            frame.body = ErrorCode::CompressionFailure.encode("response metadata too large");
            return frame;
        }
        frame.flags |= FLAG_HAS_METADATA;
    } else {
        frame.flags &= !FLAG_HAS_METADATA;
    }
    body.extend_from_slice(&compressed.bytes);
    frame.body = body;
    frame
}

fn channel_closed<T>(_: mpsc::error::SendError<T>) -> io::Error {
    io::Error::new(io::ErrorKind::BrokenPipe, "response writer closed")
}

async fn read_frame<R: AsyncRead + Unpin>(reader: &mut R, max_body_len: u32) -> io::Result<Frame> {
    let mut header = [0u8; HEADER_LEN];
    reader.read_exact(&mut header).await?;
    let decoded = Frame::decode_header(&header, max_body_len)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    let mut body = vec![0; decoded.body_len as usize];
    reader.read_exact(&mut body).await?;
    Ok(
        Frame::new(decoded.kind, decoded.method_id, decoded.request_id, body)
            .with_flags(decoded.flags),
    )
}

struct BufferedFrame {
    frame: Frame,
    permit: Option<OwnedSemaphorePermit>,
}

async fn read_frame_buffered<R: AsyncRead + Unpin>(
    reader: &mut R,
    max_body_len: u32,
    read_timeout: Duration,
    budget: Arc<Semaphore>,
) -> io::Result<BufferedFrame> {
    timeout(read_timeout, async {
        let mut header = [0u8; HEADER_LEN];
        reader.read_exact(&mut header).await?;
        let decoded = Frame::decode_header(&header, max_body_len)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        let permit = if decoded.body_len == 0 {
            None
        } else {
            Some(
                budget
                    .acquire_many_owned(decoded.body_len)
                    .await
                    .map_err(|_| {
                        io::Error::new(io::ErrorKind::BrokenPipe, "request byte budget closed")
                    })?,
            )
        };
        let mut body = vec![0; decoded.body_len as usize];
        reader.read_exact(&mut body).await?;
        Ok(BufferedFrame {
            frame: Frame::new(decoded.kind, decoded.method_id, decoded.request_id, body)
                .with_flags(decoded.flags),
            permit,
        })
    })
    .await
    .map_err(|_| {
        io::Error::new(
            io::ErrorKind::TimedOut,
            "MoonLightBridge frame read timed out",
        )
    })?
}

async fn write_frame<W: AsyncWrite + Unpin>(writer: &mut W, frame: &Frame) -> io::Result<()> {
    let encoded = frame
        .encode()
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    writer.write_all(&encoded).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;

    struct TestMiddleware {
        name: &'static str,
        events: Arc<StdMutex<Vec<String>>>,
    }

    impl Middleware for TestMiddleware {
        fn call(&self, context: RequestContext, body: Vec<u8>, next: Next) -> MiddlewareFuture {
            let name = self.name;
            let events = self.events.clone();
            Box::pin(async move {
                events.lock().unwrap().push(format!("{name}:before"));
                let result = next.run(context, body).await;
                events.lock().unwrap().push(format!("{name}:after"));
                result
            })
        }
    }

    fn test_compression() -> DecodedByteBudget {
        DecodedByteBudget::new(1024 * 1024).unwrap()
    }

    async fn dispatch_test(router: &Router, frame: Frame) -> Frame {
        let budget = test_compression();
        router
            .dispatch(
                frame,
                DEFAULT_MAX_BODY_LEN,
                MetadataLimits::default().max_bytes,
                RequestCompression {
                    codecs: moonlight_bridge_protocol::COMPRESSION_CODEC_NONE
                        | moonlight_bridge_protocol::COMPRESSION_CODEC_ZSTD,
                    policy: CompressionPolicy::default(),
                    budget: &budget,
                },
            )
            .await
    }

    #[tokio::test]
    async fn middleware_runs_in_registration_order_around_handler() {
        let events = Arc::new(StdMutex::new(Vec::new()));
        let handler_events = events.clone();
        let router = Router::builder()
            .layer(Arc::new(TestMiddleware {
                name: "outer",
                events: events.clone(),
            }))
            .layer(Arc::new(TestMiddleware {
                name: "inner",
                events: events.clone(),
            }))
            .route(1, move |body| {
                let events = handler_events.clone();
                async move {
                    events.lock().unwrap().push("handler".to_owned());
                    Ok(body)
                }
            })
            .build();

        let response = dispatch_test(
            &router,
            Frame::new(FrameKind::Request, 1, 7, b"body".to_vec()),
        )
        .await;

        assert_eq!(response.kind, FrameKind::Response);
        assert_eq!(response.body, b"body");
        assert_eq!(
            *events.lock().unwrap(),
            [
                "outer:before",
                "inner:before",
                "handler",
                "inner:after",
                "outer:after"
            ]
        );
    }

    struct RejectMiddleware;

    impl Middleware for RejectMiddleware {
        fn call(&self, _context: RequestContext, _body: Vec<u8>, _next: Next) -> MiddlewareFuture {
            Box::pin(async {
                Err(HandlerError::new(
                    ErrorCode::PermissionDenied,
                    "scope denied",
                ))
            })
        }
    }

    #[tokio::test]
    async fn middleware_short_circuit_does_not_invoke_handler() {
        let invoked = Arc::new(AtomicBool::new(false));
        let handler_invoked = invoked.clone();
        let router = Router::builder()
            .layer(Arc::new(RejectMiddleware))
            .route(1, move |_| {
                handler_invoked.store(true, Ordering::SeqCst);
                async { Ok(Vec::new()) }
            })
            .build();

        let response = dispatch_test(&router, Frame::new(FrameKind::Request, 1, 8, vec![])).await;

        assert_eq!(response.kind, FrameKind::Error);
        assert_eq!(
            ErrorCode::try_from(u16::from_be_bytes(response.body[..2].try_into().unwrap()))
                .unwrap(),
            ErrorCode::PermissionDenied
        );
        assert!(!invoked.load(Ordering::SeqCst));
    }

    struct DoubleNextMiddleware;

    impl Middleware for DoubleNextMiddleware {
        fn call(&self, context: RequestContext, body: Vec<u8>, next: Next) -> MiddlewareFuture {
            Box::pin(async move {
                let duplicate = next.clone();
                let first = next.run(context.clone(), body.clone()).await;
                assert!(first.is_ok());
                duplicate.run(context, body).await
            })
        }
    }

    #[tokio::test]
    async fn next_rejects_a_second_invocation_without_repeating_handler() {
        let calls = Arc::new(AtomicU64::new(0));
        let handler_calls = calls.clone();
        let router = Router::builder()
            .layer(Arc::new(DoubleNextMiddleware))
            .route(1, move |_| {
                handler_calls.fetch_add(1, Ordering::SeqCst);
                async { Ok(Vec::new()) }
            })
            .build();

        let response = dispatch_test(&router, Frame::new(FrameKind::Request, 1, 9, vec![])).await;

        assert_eq!(response.kind, FrameKind::Error);
        assert_eq!(
            ErrorCode::try_from(u16::from_be_bytes(response.body[..2].try_into().unwrap()))
                .unwrap(),
            ErrorCode::Internal
        );
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }

    struct ContextCapture(Arc<StdMutex<Option<RequestContext>>>);

    impl Middleware for ContextCapture {
        fn call(&self, context: RequestContext, body: Vec<u8>, next: Next) -> MiddlewareFuture {
            *self.0.lock().unwrap() = Some(context.clone());
            next.run(context, body)
        }
    }

    #[tokio::test]
    async fn middleware_context_exposes_deadline_reserved_metadata_scopes_and_tls_peer() {
        let captured = Arc::new(StdMutex::new(None));
        let router = Router::builder()
            .layer(Arc::new(ContextCapture(captured.clone())))
            .route_scoped(1, &["echo.invoke"], |body| async move { Ok(body) })
            .build();
        let mut metadata = Metadata::new();
        metadata
            .insert_reserved(
                ReservedMetadataKey::DeadlineMillis,
                1_000u32.to_be_bytes().to_vec(),
            )
            .unwrap();
        metadata
            .insert_reserved(ReservedMetadataKey::Authorization, b"opaque".to_vec())
            .unwrap();
        let mut body = Vec::new();
        metadata.encode_prefix(&mut body).unwrap();
        body.extend_from_slice(b"payload");
        let budget = test_compression();
        let peer = PeerIdentity::tls_certificate(vec![1, 2, 3]);

        let response = router
            .dispatch_with_peer(
                Frame::new(FrameKind::Request, 1, 10, body).with_flags(FLAG_HAS_METADATA),
                DEFAULT_MAX_BODY_LEN,
                MetadataLimits::default().max_bytes,
                RequestCompression {
                    codecs: moonlight_bridge_protocol::COMPRESSION_CODEC_NONE,
                    policy: CompressionPolicy::default(),
                    budget: &budget,
                },
                Some(peer.clone()),
            )
            .await;

        assert_eq!(response.body, b"payload");
        let context = captured.lock().unwrap().clone().unwrap();
        assert_eq!(context.method_id(), 1);
        assert_eq!(context.request_id(), 10);
        assert!(context.deadline().unwrap() > tokio::time::Instant::now());
        assert_eq!(context.required_scopes(), &["echo.invoke"]);
        assert_eq!(context.peer_identity(), Some(&peer));
        assert_eq!(
            context
                .metadata()
                .get(&MetadataKey::Reserved(ReservedMetadataKey::Authorization)),
            Some(b"opaque".as_slice())
        );
    }

    struct PanicMiddleware;

    impl Middleware for PanicMiddleware {
        fn call(&self, _context: RequestContext, _body: Vec<u8>, _next: Next) -> MiddlewareFuture {
            Box::pin(async { panic!("middleware panic must be isolated") })
        }
    }

    #[tokio::test]
    async fn middleware_panic_becomes_one_internal_error() {
        let router = Router::builder()
            .layer(Arc::new(PanicMiddleware))
            .route(1, |_| async { Ok(Vec::new()) })
            .build();

        let response = dispatch_test(&router, Frame::new(FrameKind::Request, 1, 11, vec![])).await;

        assert_eq!(response.kind, FrameKind::Error);
        assert_eq!(
            ErrorCode::try_from(u16::from_be_bytes(response.body[..2].try_into().unwrap()))
                .unwrap(),
            ErrorCode::Internal
        );
    }

    struct CancellationCapture(StdMutex<Option<oneshot::Sender<RequestCancellation>>>);

    impl Middleware for CancellationCapture {
        fn call(&self, context: RequestContext, _body: Vec<u8>, _next: Next) -> MiddlewareFuture {
            if let Some(sender) = self.0.lock().unwrap().take() {
                let _ = sender.send(context.cancellation().clone());
            }
            Box::pin(std::future::pending())
        }
    }

    #[tokio::test]
    async fn middleware_cancellation_signal_is_set_when_dispatch_is_aborted() {
        let (sender, receiver) = oneshot::channel();
        let router = Router::builder()
            .layer(Arc::new(CancellationCapture(StdMutex::new(Some(sender)))))
            .route(1, |_| async { Ok(Vec::new()) })
            .build();
        let task = tokio::spawn(async move {
            dispatch_test(&router, Frame::new(FrameKind::Request, 1, 12, vec![])).await
        });
        let cancellation = receiver.await.unwrap();

        task.abort();
        let _ = task.await;

        assert!(cancellation.is_cancelled());
    }

    #[tokio::test]
    async fn begin_drain_immediately_clears_readiness() {
        let settings = PeerSettings::default();
        let (_, runtime) = Server::runtime(settings);
        runtime.ready.store(true, Ordering::Relaxed);
        let handle = ServerHandle::new(runtime);

        assert!(handle.begin_drain());
        assert!(!handle.health().snapshot().ready);
        assert!(!handle.begin_drain(), "drain transition must happen once");
    }

    #[tokio::test]
    async fn server_drain_completes_existing_call_and_rejects_new_calls() {
        let (mut client, stream) = tokio::io::duplex(4096);
        let settings = PeerSettings {
            max_in_flight: 2,
            ..PeerSettings::default()
        };
        let (events, runtime) = Server::runtime(settings);
        let handle = ServerHandle::new(runtime.clone());
        let (entered_tx, entered_rx) = oneshot::channel();
        let entered_tx = Arc::new(StdMutex::new(Some(entered_tx)));
        let (release_tx, release_rx) = oneshot::channel();
        let release_rx = Arc::new(Mutex::new(Some(release_rx)));
        let router = Router::builder()
            .route(1, move |body| {
                let entered_tx = entered_tx.clone();
                let release_rx = release_rx.clone();
                async move {
                    if let Some(sender) = entered_tx.lock().unwrap().take() {
                        let _ = sender.send(());
                    }
                    if let Some(receiver) = release_rx.lock().await.take() {
                        let _ = receiver.await;
                    }
                    Ok(body)
                }
            })
            .build();
        let task = tokio::spawn(serve_test_connection(
            stream,
            router,
            settings,
            Duration::from_secs(1),
            events,
            runtime,
        ));
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Hello, 0, 0, settings.encode()),
        )
        .await
        .unwrap();
        read_frame(&mut client, settings.max_body_len)
            .await
            .unwrap();
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Request, 1, 41, b"existing".to_vec()),
        )
        .await
        .unwrap();
        entered_rx.await.unwrap();

        assert!(handle.begin_drain());
        let goaway = read_frame(&mut client, settings.max_body_len)
            .await
            .unwrap();
        assert_eq!(goaway.kind, FrameKind::GoAway);
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Request, 1, 42, b"new".to_vec()),
        )
        .await
        .unwrap();
        let rejected = read_frame(&mut client, settings.max_body_len)
            .await
            .unwrap();
        assert_eq!(rejected.kind, FrameKind::Error);
        assert_eq!(
            ErrorCode::try_from(u16::from_be_bytes(rejected.body[..2].try_into().unwrap()))
                .unwrap(),
            ErrorCode::Unavailable
        );

        let _ = release_tx.send(());
        let response = read_frame(&mut client, settings.max_body_len)
            .await
            .unwrap();
        assert_eq!(response.kind, FrameKind::Response);
        assert_eq!(response.request_id, 41);
        let goodbye = read_frame(&mut client, settings.max_body_len)
            .await
            .unwrap();
        assert_eq!(goodbye.kind, FrameKind::Goodbye);
        task.await.unwrap().unwrap();
    }

    #[tokio::test]
    async fn drain_timeout_cancels_stuck_requests_and_releases_permits() {
        let (mut client, stream) = tokio::io::duplex(4096);
        let settings = PeerSettings::default();
        let (events, runtime) = Server::runtime(settings);
        let handle = ServerHandle::new(runtime.clone());
        let router = Router::builder()
            .route(1, |_| async {
                std::future::pending::<Result<Vec<u8>, HandlerError>>().await
            })
            .build();
        let task = tokio::spawn(serve_test_connection(
            stream,
            router,
            settings,
            Duration::from_secs(1),
            events,
            runtime,
        ));
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Hello, 0, 0, settings.encode()),
        )
        .await
        .unwrap();
        read_frame(&mut client, settings.max_body_len)
            .await
            .unwrap();
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Request, 1, 51, Vec::new()),
        )
        .await
        .unwrap();
        timeout(Duration::from_secs(1), async {
            while handle.health().snapshot().active_requests == 0 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();

        assert!(!handle.drain(Duration::from_millis(10)).await);
        task.await.unwrap().unwrap();
        let health = handle.health().snapshot();
        assert_eq!(health.active_connections, 0);
        assert_eq!(health.active_requests, 0);
    }

    #[tokio::test]
    async fn begin_drain_stops_the_accept_loop() {
        let server = Server::bind_tcp(
            "127.0.0.1:0",
            Router::builder()
                .route(1, |body| async move { Ok(body) })
                .build(),
        )
        .await
        .unwrap();
        let handle = server.handle();
        let task = tokio::spawn(server.run());
        tokio::task::yield_now().await;

        assert!(handle.begin_drain());
        timeout(Duration::from_secs(1), task)
            .await
            .expect("accept loop did not stop")
            .unwrap()
            .unwrap();
    }

    async fn serve_test_connection<S>(
        stream: S,
        router: Router,
        settings: PeerSettings,
        hello_timeout: Duration,
        events: EventHub,
        runtime: Arc<RuntimeState>,
    ) -> io::Result<()>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let limits = ServerLimits::default();
        let request_bytes = Arc::new(Semaphore::new(limits.max_buffered_request_bytes));
        let connection_permits = Arc::new(Semaphore::new(1));
        let connection_permit = connection_permits.acquire_owned().await.unwrap();
        serve_connection(
            stream,
            router,
            None,
            ConnectionConfig {
                server: settings,
                hello_timeout,
                events,
            },
            runtime,
            ConnectionResources {
                limits,
                request_bytes,
                _admission: connection_permit,
            },
        )
        .await
    }

    #[derive(Default)]
    struct TraceCapture(StdMutex<Option<TraceContext>>);

    impl Telemetry for TraceCapture {
        fn start_request(&self, info: RequestInfo) -> Option<Box<dyn RequestObservation>> {
            *self.0.lock().unwrap() = info.trace_context;
            None
        }
    }

    #[derive(Default)]
    struct MethodNameCapture(StdMutex<Option<&'static str>>);

    impl Telemetry for MethodNameCapture {
        fn start_request(&self, info: RequestInfo) -> Option<Box<dyn RequestObservation>> {
            *self.0.lock().unwrap() = info.method_name;
            None
        }
    }

    #[tokio::test]
    async fn no_observation_does_not_collect_function_stages() {
        let router = Router::builder()
            .route(1, |_| async {
                assert!(FUNCTION_STAGES.try_with(|_| ()).is_err());
                Ok(trace_function("test", Vec::new))
            })
            .build();
        assert_eq!(
            router
                .dispatch(
                    Frame::new(FrameKind::Request, 1, 1, vec![]),
                    1024,
                    MetadataLimits::default().max_bytes,
                    RequestCompression {
                        codecs: moonlight_bridge_protocol::COMPRESSION_CODEC_NONE
                            | moonlight_bridge_protocol::COMPRESSION_CODEC_ZSTD,
                        policy: CompressionPolicy::default(),
                        budget: &DecodedByteBudget::new(1024 * 1024).unwrap(),
                    },
                )
                .await
                .kind,
            FrameKind::Response
        );
    }

    #[tokio::test]
    async fn goodbye_bounds_blocked_writer_shutdown() {
        let (mut client, stream) = tokio::io::duplex(256);
        let settings = PeerSettings {
            max_body_len: DEFAULT_MAX_BODY_LEN,
            max_in_flight: 1,
            features: SERVER_FEATURES,
            ..PeerSettings::default()
        };
        let (events, runtime) = Server::runtime(settings);
        let router = Router::builder()
            .route(1, |_| async { Ok(vec![0; 1024 * 1024]) })
            .build();
        let task = tokio::spawn(serve_test_connection(
            stream,
            router,
            settings,
            Duration::from_secs(1),
            events,
            runtime,
        ));
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Hello, 0, 0, settings.encode()),
        )
        .await
        .unwrap();
        read_frame(&mut client, DEFAULT_MAX_BODY_LEN).await.unwrap();
        write_frame(&mut client, &Frame::new(FrameKind::Request, 1, 1, vec![]))
            .await
            .unwrap();
        client.read_u8().await.unwrap();
        write_frame(&mut client, &Frame::new(FrameKind::Goodbye, 0, 0, vec![]))
            .await
            .unwrap();
        assert!(
            timeout(Duration::from_secs(1), task).await.is_ok(),
            "GOODBYE must bound writer drain"
        );
    }

    #[tokio::test]
    async fn dispatch_strips_trace_metadata_and_records_metrics() {
        let metrics = MoonLightMetrics::default();
        let trace_capture = Arc::new(TraceCapture::default());
        let method_name_capture = Arc::new(MethodNameCapture::default());
        let chain = TelemetryChain::new([
            Arc::new(metrics.clone()) as Arc<dyn Telemetry>,
            trace_capture.clone() as Arc<dyn Telemetry>,
            method_name_capture.clone() as Arc<dyn Telemetry>,
        ]);
        let router = Router::builder()
            .telemetry(Arc::new(chain))
            .route(7, |body| async move { Ok(body) })
            .method_name(7, "MonitoringService/Ping")
            .build();
        let expected_trace = TraceContext {
            trace_id: [3; 16],
            parent_span_id: [4; 8],
            sampled: true,
        };
        let mut metadata = moonlight_bridge_protocol::Metadata::new();
        metadata
            .insert_reserved(
                moonlight_bridge_protocol::ReservedMetadataKey::DeadlineMillis,
                1_000u32.to_be_bytes().to_vec(),
            )
            .unwrap();
        let mut encoded_trace = Vec::new();
        expected_trace.encode_into(&mut encoded_trace);
        metadata
            .insert_reserved(
                moonlight_bridge_protocol::ReservedMetadataKey::TraceContext,
                encoded_trace,
            )
            .unwrap();
        let mut body = Vec::new();
        metadata.encode_prefix(&mut body).unwrap();
        body.extend_from_slice(b"payload");
        let response = router
            .dispatch(
                Frame::new(FrameKind::Request, 7, 9, body)
                    .with_flags(moonlight_bridge_protocol::FLAG_HAS_METADATA),
                DEFAULT_MAX_BODY_LEN,
                MetadataLimits::default().max_bytes,
                RequestCompression {
                    codecs: moonlight_bridge_protocol::COMPRESSION_CODEC_NONE
                        | moonlight_bridge_protocol::COMPRESSION_CODEC_ZSTD,
                    policy: CompressionPolicy::default(),
                    budget: &DecodedByteBudget::new(1024 * 1024).unwrap(),
                },
            )
            .await;

        assert_eq!(response.body, b"payload");
        assert_eq!(*trace_capture.0.lock().unwrap(), Some(expected_trace));
        assert_eq!(
            *method_name_capture.0.lock().unwrap(),
            Some("MonitoringService/Ping")
        );
        assert_eq!(metrics.snapshot().started, 1);
        assert_eq!(metrics.snapshot().succeeded, 1);
        assert_eq!(metrics.snapshot().request_bytes, 7);
        assert_eq!(metrics.snapshot().response_bytes, 7);
    }

    #[test]
    fn request_payload_decompresses_zstd_after_bounded_metadata_validation() {
        let policy = moonlight_bridge_protocol::CompressionPolicy {
            min_payload_bytes: 1,
            min_savings_bytes: 0,
            max_decoded_body_len: 1024 * 1024,
            max_expansion_ratio: 256,
            compression_level: 1,
        };
        let original = vec![b'x'; 4096];
        let compressed = policy
            .encode(
                original.clone(),
                moonlight_bridge_protocol::CompressionCodec::Zstd,
            )
            .unwrap();
        assert_eq!(
            compressed.codec,
            moonlight_bridge_protocol::CompressionCodec::Zstd
        );
        let mut metadata = moonlight_bridge_protocol::Metadata::new();
        metadata
            .insert_reserved(
                moonlight_bridge_protocol::ReservedMetadataKey::CompressionCodec,
                vec![compressed.codec as u8],
            )
            .unwrap();
        metadata
            .insert_reserved(
                moonlight_bridge_protocol::ReservedMetadataKey::OriginalLength,
                compressed.original_len.to_be_bytes().to_vec(),
            )
            .unwrap();
        let mut body = Vec::new();
        metadata.encode_prefix(&mut body).unwrap();
        body.extend_from_slice(&compressed.bytes);
        let mut frame = Frame::new(FrameKind::Request, 1, 1, body)
            .with_flags(moonlight_bridge_protocol::FLAG_HAS_METADATA);
        let budget = moonlight_bridge_protocol::DecodedByteBudget::new(8192).unwrap();

        let parsed = request_payload(
            &mut frame,
            MetadataLimits::default().max_bytes,
            RequestCompression {
                codecs: moonlight_bridge_protocol::COMPRESSION_CODEC_NONE
                    | moonlight_bridge_protocol::COMPRESSION_CODEC_ZSTD,
                policy,
                budget: &budget,
            },
        )
        .unwrap();
        assert_eq!(parsed.payload, original);
    }

    #[test]
    fn dropped_observation_records_cancellation() {
        let metrics = MoonLightMetrics::default();
        let observation = metrics.start_request(RequestInfo {
            method_id: 1,
            method_name: None,
            request_id: 2,
            request_bytes: 3,
            trace_context: None,
        });
        drop(observation);
        assert_eq!(metrics.snapshot().started, 1);
        assert_eq!(metrics.snapshot().failed, 1);
    }

    #[test]
    #[should_panic(expected = "duplicate MoonLightBridge method ID")]
    fn duplicate_routes_are_rejected_during_registration() {
        let _ = Router::builder()
            .route(7, |_| async { Ok(Vec::new()) })
            .route(7, |_| async { Ok(Vec::new()) });
    }

    #[tokio::test]
    async fn silent_client_is_rejected_by_hello_timeout() {
        let (_client, server_stream) = tokio::io::duplex(256);
        let settings = PeerSettings::default();
        let (events, runtime) = Server::runtime(settings);
        let error = serve_test_connection(
            server_stream,
            Router::builder().build(),
            settings,
            Duration::from_millis(5),
            events,
            runtime,
        )
        .await
        .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
    }

    #[tokio::test]
    async fn duplicate_active_request_id_terminates_connection_and_cleans_up() {
        let (mut client, server_stream) = tokio::io::duplex(4_096);
        let settings = PeerSettings::default();
        let (events, runtime) = Server::runtime(settings);
        let router = Router::builder()
            .route(1, |_| async {
                tokio::time::sleep(Duration::from_secs(30)).await;
                Ok(Vec::new())
            })
            .build();
        let task = tokio::spawn(serve_test_connection(
            server_stream,
            router,
            settings,
            Duration::from_secs(1),
            events,
            runtime.clone(),
        ));
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Hello, 0, 0, settings.encode()),
        )
        .await
        .unwrap();
        assert_eq!(
            read_frame(&mut client, PeerSettings::BODY_LEN as u32)
                .await
                .unwrap()
                .kind,
            FrameKind::Welcome
        );
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Request, 1, 9, Vec::new()),
        )
        .await
        .unwrap();
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Request, 1, 9, Vec::new()),
        )
        .await
        .unwrap();
        let error = timeout(Duration::from_secs(1), task)
            .await
            .unwrap()
            .unwrap()
            .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert_eq!(runtime.active_connections.load(Ordering::Relaxed), 0);
        assert_eq!(runtime.active_requests.load(Ordering::Relaxed), 0);
    }

    #[tokio::test]
    async fn event_does_not_cancel_a_partially_read_client_frame() {
        let (mut client, server_stream) = tokio::io::duplex(4_096);
        let settings = PeerSettings::default();
        let (events, runtime) = Server::runtime(settings);
        let publish_events = events.clone();
        let task = tokio::spawn(serve_test_connection(
            server_stream,
            Router::builder().build(),
            settings,
            Duration::from_secs(1),
            events,
            runtime,
        ));
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Hello, 0, 0, settings.encode()),
        )
        .await
        .unwrap();
        assert_eq!(
            read_frame(&mut client, PeerSettings::BODY_LEN as u32)
                .await
                .unwrap()
                .kind,
            FrameKind::Welcome
        );

        let ping = Frame::new(FrameKind::Ping, 0, 77, b"partial-ping".to_vec())
            .encode()
            .unwrap();
        client.write_all(&ping[..8]).await.unwrap();
        tokio::time::sleep(Duration::from_millis(10)).await;
        assert_eq!(publish_events.publish(91, b"event".to_vec()), 1);
        let event = timeout(
            Duration::from_secs(1),
            read_frame(&mut client, DEFAULT_MAX_BODY_LEN),
        )
        .await
        .unwrap()
        .unwrap();
        assert_eq!(event.kind, FrameKind::Event);
        assert_eq!(event.method_id, 91);

        client.write_all(&ping[8..]).await.unwrap();
        let pong = timeout(
            Duration::from_secs(1),
            read_frame(&mut client, DEFAULT_MAX_BODY_LEN),
        )
        .await
        .unwrap()
        .unwrap();
        assert_eq!(pong.kind, FrameKind::Pong);
        assert_eq!(pong.request_id, 77);
        assert_eq!(pong.body, b"partial-ping");

        write_frame(
            &mut client,
            &Frame::new(FrameKind::Goodbye, 0, 0, Vec::new()),
        )
        .await
        .unwrap();
        task.await.unwrap().unwrap();
    }

    #[tokio::test]
    async fn oversized_handler_response_is_replaced_before_writing() {
        let (mut client, server_stream) = tokio::io::duplex(4_096);
        let client_settings = moonlight_bridge_protocol::PeerSettingsV2 {
            max_body_len: 64,
            max_decoded_body_len: 128,
            max_metadata_len: 1_024,
            max_in_flight: DEFAULT_MAX_IN_FLIGHT,
            max_concurrent_streams: 8,
            initial_stream_credit: 4,
            compression_codecs: 1,
            features: SERVER_FEATURES,
            diagnostic_features: 0,
        };
        let server_settings = PeerSettings::default();
        let (events, runtime) = Server::runtime(server_settings);
        let router = Router::builder()
            .route(1, |_| async { Ok(vec![7; 128]) })
            .build();
        let task = tokio::spawn(serve_test_connection(
            server_stream,
            router,
            server_settings,
            Duration::from_secs(1),
            events,
            runtime,
        ));
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Hello, 0, 0, client_settings.encode()),
        )
        .await
        .unwrap();
        assert_eq!(
            read_frame(
                &mut client,
                moonlight_bridge_protocol::PeerSettingsV2::BODY_LEN as u32,
            )
            .await
            .unwrap()
            .kind,
            FrameKind::Welcome
        );
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Request, 1, 88, Vec::new()),
        )
        .await
        .unwrap();

        let response = read_frame(&mut client, client_settings.max_body_len)
            .await
            .unwrap();
        assert_eq!(response.kind, FrameKind::Error);
        assert_eq!(response.request_id, 88);
        assert!(response.body.len() <= client_settings.max_body_len as usize);
        assert_eq!(
            u16::from_be_bytes(response.body[..2].try_into().unwrap()),
            ErrorCode::ResourceExhausted as u16
        );

        write_frame(
            &mut client,
            &Frame::new(FrameKind::Goodbye, 0, 0, Vec::new()),
        )
        .await
        .unwrap();
        task.await.unwrap().unwrap();
    }

    #[tokio::test]
    async fn compressible_response_may_exceed_encoded_but_not_decoded_limit() {
        let (mut client, server_stream) = tokio::io::duplex(8_192);
        let client_settings = moonlight_bridge_protocol::PeerSettingsV2 {
            max_body_len: 256,
            max_decoded_body_len: 4_096,
            max_metadata_len: 1_024,
            max_in_flight: DEFAULT_MAX_IN_FLIGHT,
            max_concurrent_streams: 8,
            initial_stream_credit: 4,
            compression_codecs: moonlight_bridge_protocol::COMPRESSION_CODEC_NONE
                | moonlight_bridge_protocol::COMPRESSION_CODEC_ZSTD,
            features: SERVER_FEATURES,
            diagnostic_features: 0,
        };
        let block: Vec<u8> = (0..128)
            .map(|index| ((index * 73 + 19) % 251) as u8)
            .collect();
        let expected: Vec<u8> = block.iter().copied().cycle().take(4_096).collect();
        let response_body = expected.clone();
        let server_settings = PeerSettings::default();
        let (events, runtime) = Server::runtime(server_settings);
        let router = Router::builder()
            .route(1, move |_| {
                let response_body = response_body.clone();
                async move { Ok(response_body) }
            })
            .build();
        let task = tokio::spawn(serve_test_connection(
            server_stream,
            router,
            server_settings,
            Duration::from_secs(1),
            events,
            runtime,
        ));
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Hello, 0, 0, client_settings.encode()),
        )
        .await
        .unwrap();
        read_frame(
            &mut client,
            moonlight_bridge_protocol::PeerSettingsV2::BODY_LEN as u32,
        )
        .await
        .unwrap();
        write_frame(
            &mut client,
            &Frame::new(FrameKind::Request, 1, 89, Vec::new()),
        )
        .await
        .unwrap();

        let response = read_frame(&mut client, client_settings.max_body_len)
            .await
            .unwrap();
        assert_eq!(response.kind, FrameKind::Response);
        assert_ne!(response.flags & FLAG_HAS_METADATA, 0);
        let (metadata, compressed) = Metadata::decode(
            &response.body,
            MetadataLimits {
                max_bytes: client_settings.max_metadata_len,
                ..MetadataLimits::default()
            },
        )
        .unwrap();
        let original_len = metadata
            .get(&MetadataKey::Reserved(ReservedMetadataKey::OriginalLength))
            .unwrap();
        let decoded = CompressionPolicy {
            max_decoded_body_len: client_settings.max_decoded_body_len,
            ..CompressionPolicy::default()
        }
        .decode(
            CompressionCodec::Zstd,
            compressed,
            u32::from_be_bytes(original_len.try_into().unwrap()),
            &DecodedByteBudget::new(8_192).unwrap(),
        )
        .unwrap();
        assert_eq!(decoded, expected);

        write_frame(
            &mut client,
            &Frame::new(FrameKind::Goodbye, 0, 0, Vec::new()),
        )
        .await
        .unwrap();
        task.await.unwrap().unwrap();
    }
}
