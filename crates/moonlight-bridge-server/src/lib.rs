#![doc = include_str!("../README.md")]

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
    sync::{Mutex, Semaphore, broadcast, mpsc, oneshot},
    task::AbortHandle,
    time::timeout,
};
use tokio_rustls::{TlsAcceptor, rustls::ServerConfig};
use tracing::Instrument;

pub mod idempotency;
pub mod tls;

tokio::task_local! {
    static FUNCTION_STAGES: RefCell<Vec<(&'static str, Duration)>>;
}

/// Traces a synchronous backend function as a child stage when tracing is enabled.
pub fn trace_function<T>(name: &'static str, function: impl FnOnce() -> T) -> T {
    let started = Instant::now();
    let span = tracing::info_span!(target: "moonlight_bridge::function", "backend.function", function = name);
    let _entered = span.enter();
    let output = function();
    let _ = FUNCTION_STAGES.try_with(|stages| stages.borrow_mut().push((name, started.elapsed())));
    output
}

/// Traces an asynchronous backend function as a child stage when tracing is enabled.
pub async fn trace_async_function<T>(name: &'static str, future: impl Future<Output = T>) -> T {
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
type ActiveRequests = Arc<Mutex<HashMap<u64, AbortHandle>>>;

#[derive(Clone)]
pub struct EventHub {
    sender: broadcast::Sender<Frame>,
}

impl EventHub {
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
pub struct ServerHealth {
    pub ready: bool,
    pub active_connections: u64,
    pub active_requests: u64,
    pub max_in_flight_per_connection: u32,
    pub uptime: Duration,
}

#[derive(Clone)]
pub struct HealthHandle(Arc<RuntimeState>);

impl HealthHandle {
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
pub struct RequestInfo {
    pub method_id: u32,
    pub request_id: u64,
    pub request_bytes: usize,
    pub trace_context: Option<TraceContext>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RequestOutcome {
    Success { response_bytes: usize },
    Error { code: ErrorCode },
}

pub trait RequestObservation: Send {
    fn record_stage(&mut self, _name: &'static str, _duration: Duration) {}
    fn finish(self: Box<Self>, outcome: RequestOutcome);
}

pub trait Telemetry: Send + Sync {
    fn start_request(&self, info: RequestInfo) -> Option<Box<dyn RequestObservation>>;
}

#[derive(Default)]
pub struct TelemetryChain(Vec<Arc<dyn Telemetry>>);

impl TelemetryChain {
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
pub struct MetricsSnapshot {
    pub started: u64,
    pub succeeded: u64,
    pub failed: u64,
    pub request_bytes: u64,
    pub response_bytes: u64,
    pub total_latency_nanos: u64,
    pub max_latency_nanos: u64,
}

impl MoonLightMetrics {
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
pub struct HandlerError {
    pub code: ErrorCode,
    pub message: String,
}

impl HandlerError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Internal, message)
    }
}

#[derive(Clone, Default)]
pub struct Router {
    handlers: Arc<HashMap<u32, Handler>>,
    telemetry: Option<Arc<dyn Telemetry>>,
}

pub struct RouterBuilder {
    handlers: HashMap<u32, Handler>,
    telemetry: Option<Arc<dyn Telemetry>>,
}

impl Router {
    pub fn builder() -> RouterBuilder {
        RouterBuilder {
            handlers: HashMap::new(),
            telemetry: None,
        }
    }

    async fn dispatch(&self, mut frame: Frame) -> Frame {
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
        let instrumented = FUNCTION_STAGES.scope(RefCell::new(Vec::new()), async move {
            let result = handler(payload).await;
            let stages = FUNCTION_STAGES.with(|stages| std::mem::take(&mut *stages.borrow_mut()));
            (result, stages)
        });
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
}

impl RouterBuilder {
    pub fn telemetry(mut self, telemetry: Arc<dyn Telemetry>) -> Self {
        self.telemetry = Some(telemetry);
        self
    }

    pub fn route<F, Fut>(mut self, method_id: u32, handler: F) -> Self
    where
        F: Fn(Vec<u8>) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = Result<Vec<u8>, HandlerError>> + Send + 'static,
    {
        let previous = self
            .handlers
            .insert(method_id, Arc::new(move |body| Box::pin(handler(body))));
        assert!(
            previous.is_none(),
            "duplicate MoonLightBridge method ID registered: 0x{method_id:08X}"
        );
        self
    }

    pub fn build(self) -> Router {
        Router {
            handlers: Arc::new(self.handlers),
            telemetry: self.telemetry,
        }
    }
}

fn finish_observation(observation: Option<Box<dyn RequestObservation>>, outcome: RequestOutcome) {
    if let Some(observation) = observation {
        observation.finish(outcome);
    }
}

pub struct Server {
    listener: Listener,
    router: Router,
    settings: PeerSettings,
    hello_timeout: Duration,
    events: EventHub,
    runtime: Arc<RuntimeState>,
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

    pub async fn bind(address: &str, router: Router) -> io::Result<Self> {
        Self::bind_tcp(address, router).await
    }

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
        })
    }

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
        })
    }

    #[cfg(unix)]
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

    pub fn events(&self) -> EventHub {
        self.events.clone()
    }

    /// Installs a pre-created hub so application handlers can publish events.
    pub fn with_event_hub(mut self, events: EventHub) -> Self {
        self.events = events;
        self
    }

    pub fn health(&self) -> HealthHandle {
        HealthHandle(self.runtime.clone())
    }

    pub async fn run(self) -> io::Result<()> {
        self.runtime.ready.store(true, Ordering::Relaxed);
        match self.listener {
            Listener::Tcp(listener) => loop {
                let (stream, _) = listener.accept().await?;
                stream.set_nodelay(true)?;
                let router = self.router.clone();
                let settings = self.settings;
                let hello_timeout = self.hello_timeout;
                let events = self.events.clone();
                let runtime = self.runtime.clone();
                tokio::spawn(async move {
                    if let Err(error) =
                        serve_connection(stream, router, settings, hello_timeout, events, runtime)
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
                tokio::spawn(async move {
                    if let Err(error) =
                        serve_connection(stream, router, settings, hello_timeout, events, runtime)
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
) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
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
    let active: ActiveRequests = Arc::new(Mutex::new(HashMap::new()));
    let permits = Arc::new(Semaphore::new(negotiated.max_in_flight as usize));
    let mut event_rx = events.sender.subscribe();

    let writer_task = tokio::spawn(async move {
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
            encoded.clear();
            first
                .encode_into(&mut encoded)
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
            let mut frames = 1;
            while frames < MAX_BATCH_FRAMES {
                let Ok(frame) = responses_rx.try_recv() else {
                    break;
                };
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
        let frame = tokio::select! {
            result = read_frame(&mut reader, negotiated.max_body_len) => match result {
                Ok(frame) => frame,
                Err(error) if error.kind() == io::ErrorKind::UnexpectedEof => break Ok(()),
                Err(error) => break Err(error),
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
                let (start_tx, start_rx) = oneshot::channel();
                let task = tokio::spawn(async move {
                    let _permit = permit;
                    runtime_for_task
                        .active_requests
                        .fetch_add(1, Ordering::Relaxed);
                    let _request_guard = RequestGuard(runtime_for_task);
                    if start_rx.await.is_err() {
                        return;
                    }
                    let response = router.dispatch(frame).await;
                    active_for_task.lock().await.remove(&request_id);
                    let _ = responses_tx.send(response).await;
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
                active_requests.insert(request_id, task.abort_handle());
                drop(active_requests);
                let _ = start_tx.send(());
            }
            FrameKind::Cancel => {
                if let Some(handle) = active.lock().await.remove(&frame.request_id) {
                    handle.abort();
                }
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
    for (_, handle) in active.lock().await.drain() {
        handle.abort();
    }
    drop(responses_tx);
    let writer_result = writer_task.await.map_err(io::Error::other)?;
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

    #[derive(Default)]
    struct TraceCapture(StdMutex<Option<TraceContext>>);

    impl Telemetry for TraceCapture {
        fn start_request(&self, info: RequestInfo) -> Option<Box<dyn RequestObservation>> {
            *self.0.lock().unwrap() = info.trace_context;
            None
        }
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
        let error = serve_connection(
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
        let task = tokio::spawn(serve_connection(
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
}
