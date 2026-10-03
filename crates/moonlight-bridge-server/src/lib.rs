#![doc = include_str!("../README.md")]

use futures_core::Stream;
use futures_util::StreamExt;
pub use moonlight_bridge_protocol::ErrorCode;
use moonlight_bridge_protocol::{
    DEFAULT_MAX_BODY_LEN, DEFAULT_MAX_IN_FLIGHT, FLAG_HAS_DEADLINE, FLAG_HAS_TRACE_CONTEXT, Frame,
    FrameKind, HEADER_LEN, PeerSettings, SERVER_FEATURES, TRACE_CONTEXT_LEN, TraceContext,
};
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
    sync::{Mutex, OwnedSemaphorePermit, Semaphore, broadcast, mpsc, oneshot},
    task::AbortHandle,
    time::timeout,
};
use tokio_rustls::{TlsAcceptor, rustls::ServerConfig};
use tracing::Instrument;

pub mod idempotency;
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
type Handler = Arc<dyn Fn(Vec<u8>) -> HandlerFuture + Send + Sync>;
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
    started: Instant,
}

struct ConnectionGuard(Arc<RuntimeState>);

impl Drop for ConnectionGuard {
    fn drop(&mut self) {
        self.0.active_connections.fetch_sub(1, Ordering::Relaxed);
    }
}

struct RequestGuard(Arc<RuntimeState>);

impl Drop for RequestGuard {
    fn drop(&mut self) {
        self.0.active_requests.fetch_sub(1, Ordering::Relaxed);
    }
}

#[derive(Debug, Clone, Copy)]
/// Request metadata supplied to a [`Telemetry`] implementation.
pub struct RequestInfo {
    /// Generated method identifier.
    pub method_id: u32,
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
    telemetry: Option<Arc<dyn Telemetry>>,
}

/// Builder that registers handlers and request telemetry.
pub struct RouterBuilder {
    handlers: HashMap<u32, Handler>,
    stream_handlers: HashMap<u32, StreamHandler>,
    telemetry: Option<Arc<dyn Telemetry>>,
}

impl Router {
    /// Starts an empty router builder.
    pub fn builder() -> RouterBuilder {
        RouterBuilder {
            handlers: HashMap::new(),
            stream_handlers: HashMap::new(),
            telemetry: None,
        }
    }

    async fn dispatch(&self, mut frame: Frame, max_response_body_len: u32) -> Frame {
        let ParsedRequest {
            deadline,
            trace_context,
            payload,
        } = match request_payload(&mut frame) {
            Ok(parts) => parts,
            Err(message) => return error_frame(&frame, ErrorCode::InvalidRequest, message),
        };
        let mut observation = self.telemetry.as_ref().and_then(|telemetry| {
            telemetry.start_request(RequestInfo {
                method_id: frame.method_id,
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
        let handler = handler.clone();
        let collect_stages = observation.is_some();
        let instrumented = async move {
            if collect_stages {
                FUNCTION_STAGES
                    .scope(RefCell::new(Vec::new()), async move {
                        let result = handler(payload).await;
                        let stages = FUNCTION_STAGES
                            .with(|stages| std::mem::take(&mut *stages.borrow_mut()));
                        (result, stages)
                    })
                    .await
            } else {
                (handler(payload).await, Vec::new())
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
        credits: Arc<Semaphore>,
        responses: mpsc::Sender<Frame>,
    ) {
        let ParsedRequest {
            deadline,
            trace_context,
            payload,
        } = match request_payload(&mut frame) {
            Ok(parts) => parts,
            Err(message) => {
                let _ = responses
                    .send(error_frame(&frame, ErrorCode::InvalidRequest, message))
                    .await;
                return;
            }
        };
        let observation = self.telemetry.as_ref().and_then(|telemetry| {
            telemetry.start_request(RequestInfo {
                method_id: frame.method_id,
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
}

impl RouterBuilder {
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

    /// Freezes handler registration into a cloneable router.
    pub fn build(self) -> Router {
        Router {
            handlers: Arc::new(self.handlers),
            stream_handlers: Arc::new(self.stream_handlers),
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
        (
            EventHub::default(),
            Arc::new(RuntimeState {
                ready: AtomicBool::new(false),
                active_connections: AtomicU64::new(0),
                active_requests: AtomicU64::new(0),
                max_in_flight: settings.max_in_flight,
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
        let settings = PeerSettings {
            max_body_len: DEFAULT_MAX_BODY_LEN,
            max_in_flight: DEFAULT_MAX_IN_FLIGHT,
            features: SERVER_FEATURES,
        };
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
        let settings = PeerSettings {
            max_body_len: DEFAULT_MAX_BODY_LEN,
            max_in_flight: DEFAULT_MAX_IN_FLIGHT,
            features: SERVER_FEATURES,
        };
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
        let settings = PeerSettings {
            max_body_len: DEFAULT_MAX_BODY_LEN,
            max_in_flight: DEFAULT_MAX_IN_FLIGHT,
            features: SERVER_FEATURES,
        };
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

    /// Runs the accept loop until an unrecoverable listener error occurs.
    pub async fn run(self) -> io::Result<()> {
        self.runtime.ready.store(true, Ordering::Relaxed);
        let connection_permits = Arc::new(Semaphore::new(self.limits.max_connections));
        let request_bytes = Arc::new(Semaphore::new(self.limits.max_buffered_request_bytes));
        match self.listener {
            Listener::Tcp(listener) => loop {
                let (stream, _) = listener.accept().await?;
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
                        settings,
                        hello_timeout,
                        events,
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
                let (stream, _) = listener.accept().await?;
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
                            if let Err(error) = serve_connection(
                                stream,
                                router,
                                settings,
                                hello_timeout,
                                events,
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
                let (stream, _) = listener.accept().await?;
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
                        settings,
                        hello_timeout,
                        events,
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
    server: PeerSettings,
    hello_timeout: Duration,
    events: EventHub,
    runtime: Arc<RuntimeState>,
    resources: ConnectionResources,
) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
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
        max_in_flight: client.max_in_flight.min(server.max_in_flight),
        features: client.features & server.features,
    };
    write_frame(
        &mut stream,
        &Frame::new(FrameKind::Welcome, 0, 0, negotiated.encode()),
    )
    .await?;
    tracing::debug!(
        max_body_len = negotiated.max_body_len,
        max_in_flight = negotiated.max_in_flight,
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
                        if frame.body.len() > negotiated.max_body_len as usize {
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
            }
        };
        let BufferedFrame {
            frame,
            permit: body_permit,
        } = buffered;
        match frame.kind {
            FrameKind::Request => {
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
                let request_id = frame.request_id;
                let runtime_for_task = runtime.clone();
                let max_body_len = negotiated.max_body_len;
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
                    let _request_guard = RequestGuard(runtime_for_task);
                    if start_rx.await.is_err() {
                        return;
                    }
                    if let Some(credits) = task_credits {
                        router
                            .dispatch_stream(frame, max_body_len, credits, responses_tx.clone())
                            .await;
                    } else {
                        let response = fit_outbound_frame(
                            router.dispatch(frame, max_body_len).await,
                            max_body_len,
                        );
                        let _ = responses_tx.send(response).await;
                    }
                    active_for_task.lock().await.remove(&request_id);
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
    payload: Vec<u8>,
}

fn request_payload(frame: &mut Frame) -> Result<ParsedRequest, &'static str> {
    let mut offset = 0;
    let deadline = if frame.flags & FLAG_HAS_DEADLINE != 0 {
        if frame.body.len() < offset + 4 {
            return Err("deadline flag requires a four-byte timeout");
        }
        let millis = u32::from_be_bytes(frame.body[offset..offset + 4].try_into().unwrap());
        if millis == 0 {
            return Err("deadline must be greater than zero");
        }
        offset += 4;
        Some(Duration::from_millis(millis as u64))
    } else {
        None
    };
    let trace_context = if frame.flags & FLAG_HAS_TRACE_CONTEXT != 0 {
        if frame.body.len() < offset + TRACE_CONTEXT_LEN {
            return Err("trace-context flag requires a 25-byte context");
        }
        let context = TraceContext::decode(&frame.body[offset..offset + TRACE_CONTEXT_LEN])
            .map_err(|_| "invalid trace context")?;
        offset += TRACE_CONTEXT_LEN;
        Some(context)
    } else {
        None
    };
    let payload_length = frame.body.len() - offset;
    frame.body.copy_within(offset.., 0);
    frame.body.truncate(payload_length);
    Ok(ParsedRequest {
        deadline,
        trace_context,
        payload: std::mem::take(&mut frame.body),
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
            settings,
            hello_timeout,
            events,
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
                .dispatch(Frame::new(FrameKind::Request, 1, 1, vec![]), 1024)
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
        let chain = TelemetryChain::new([
            Arc::new(metrics.clone()) as Arc<dyn Telemetry>,
            trace_capture.clone() as Arc<dyn Telemetry>,
        ]);
        let router = Router::builder()
            .telemetry(Arc::new(chain))
            .route(7, |body| async move { Ok(body) })
            .build();
        let expected_trace = TraceContext {
            trace_id: [3; 16],
            parent_span_id: [4; 8],
            sampled: true,
        };
        let mut body = 1_000u32.to_be_bytes().to_vec();
        expected_trace.encode_into(&mut body);
        body.extend_from_slice(b"payload");
        let response = router
            .dispatch(
                Frame::new(FrameKind::Request, 7, 9, body)
                    .with_flags(FLAG_HAS_DEADLINE | FLAG_HAS_TRACE_CONTEXT),
                DEFAULT_MAX_BODY_LEN,
            )
            .await;

        assert_eq!(response.body, b"payload");
        assert_eq!(*trace_capture.0.lock().unwrap(), Some(expected_trace));
        assert_eq!(metrics.snapshot().started, 1);
        assert_eq!(metrics.snapshot().succeeded, 1);
        assert_eq!(metrics.snapshot().request_bytes, 7);
        assert_eq!(metrics.snapshot().response_bytes, 7);
    }

    #[test]
    fn dropped_observation_records_cancellation() {
        let metrics = MoonLightMetrics::default();
        let observation = metrics.start_request(RequestInfo {
            method_id: 1,
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
        let settings = PeerSettings {
            max_body_len: DEFAULT_MAX_BODY_LEN,
            max_in_flight: DEFAULT_MAX_IN_FLIGHT,
            features: SERVER_FEATURES,
        };
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
        let settings = PeerSettings {
            max_body_len: DEFAULT_MAX_BODY_LEN,
            max_in_flight: DEFAULT_MAX_IN_FLIGHT,
            features: SERVER_FEATURES,
        };
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
        let settings = PeerSettings {
            max_body_len: DEFAULT_MAX_BODY_LEN,
            max_in_flight: DEFAULT_MAX_IN_FLIGHT,
            features: SERVER_FEATURES,
        };
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
        let client_settings = PeerSettings {
            max_body_len: 64,
            max_in_flight: DEFAULT_MAX_IN_FLIGHT,
            features: SERVER_FEATURES,
        };
        let server_settings = PeerSettings {
            max_body_len: DEFAULT_MAX_BODY_LEN,
            max_in_flight: DEFAULT_MAX_IN_FLIGHT,
            features: SERVER_FEATURES,
        };
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
            read_frame(&mut client, PeerSettings::BODY_LEN as u32)
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
}
