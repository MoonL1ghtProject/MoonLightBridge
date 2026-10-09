use crate::{Handler, HandlerError};
use futures_util::FutureExt;
use moonlight_bridge_protocol::Metadata;
use std::{
    future::Future,
    panic::AssertUnwindSafe,
    pin::Pin,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
};
use tokio::time::Instant;

/// Boxed result returned by server middleware.
pub type MiddlewareFuture =
    Pin<Box<dyn Future<Output = Result<Vec<u8>, HandlerError>> + Send + 'static>>;

/// Immutable identity extracted from the authenticated transport.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PeerIdentity {
    tls_leaf_certificate: Arc<[u8]>,
}

impl PeerIdentity {
    /// Creates an identity from the DER-encoded authenticated TLS leaf certificate.
    pub fn tls_certificate(certificate: Vec<u8>) -> Self {
        Self {
            tls_leaf_certificate: certificate.into(),
        }
    }

    /// Returns the DER-encoded authenticated TLS leaf certificate.
    pub fn tls_leaf_certificate(&self) -> &[u8] {
        &self.tls_leaf_certificate
    }
}

/// Cloneable cancellation signal associated with one request execution.
#[derive(Clone, Debug)]
pub struct RequestCancellation(Arc<AtomicBool>);

impl RequestCancellation {
    pub(crate) fn new() -> Self {
        Self(Arc::new(AtomicBool::new(false)))
    }

    pub(crate) fn cancel(&self) {
        self.0.store(true, Ordering::Release);
    }

    /// Returns whether the request future was cancelled or exceeded its deadline.
    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }
}

/// Immutable transport and generated-policy context for one RPC request.
#[derive(Clone, Debug)]
pub struct RequestContext {
    method_id: u32,
    request_id: u64,
    deadline: Option<Instant>,
    metadata: Metadata,
    required_scopes: &'static [&'static str],
    peer_identity: Option<PeerIdentity>,
    cancellation: RequestCancellation,
}

impl RequestContext {
    pub(crate) fn new(
        method_id: u32,
        request_id: u64,
        deadline: Option<Instant>,
        metadata: Metadata,
        required_scopes: &'static [&'static str],
        peer_identity: Option<PeerIdentity>,
        cancellation: RequestCancellation,
    ) -> Self {
        Self {
            method_id,
            request_id,
            deadline,
            metadata,
            required_scopes,
            peer_identity,
            cancellation,
        }
    }

    /// Returns the generated method identifier.
    pub fn method_id(&self) -> u32 {
        self.method_id
    }

    /// Returns the connection-local request identifier.
    pub fn request_id(&self) -> u64 {
        self.request_id
    }

    /// Returns the absolute Tokio deadline, when the caller supplied one.
    pub fn deadline(&self) -> Option<Instant> {
        self.deadline
    }

    /// Returns all validated request metadata, including runtime-owned entries.
    pub fn metadata(&self) -> &Metadata {
        &self.metadata
    }

    /// Returns authorization scopes compiled from the method schema.
    pub fn required_scopes(&self) -> &'static [&'static str] {
        self.required_scopes
    }

    /// Returns the authenticated TLS peer identity, when the transport supplied one.
    pub fn peer_identity(&self) -> Option<&PeerIdentity> {
        self.peer_identity.as_ref()
    }

    /// Returns a cloneable request cancellation signal.
    pub fn cancellation(&self) -> &RequestCancellation {
        &self.cancellation
    }
}

/// One compiled server middleware layer.
pub trait Middleware: Send + Sync + 'static {
    /// Processes a request and optionally invokes the remainder of the chain.
    fn call(&self, context: RequestContext, body: Vec<u8>, next: Next) -> MiddlewareFuture;
}

/// Single-use continuation for the remainder of a middleware chain.
#[derive(Clone)]
pub struct Next {
    layers: Arc<[Arc<dyn Middleware>]>,
    index: usize,
    handler: Handler,
    called: Arc<AtomicBool>,
}

impl Next {
    pub(crate) fn root(layers: Arc<[Arc<dyn Middleware>]>, handler: Handler) -> Self {
        Self {
            layers,
            index: 0,
            handler,
            called: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Invokes the next layer exactly once.
    pub fn run(&self, context: RequestContext, body: Vec<u8>) -> MiddlewareFuture {
        if self
            .called
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return Box::pin(async {
                Err(HandlerError::internal(
                    "middleware continuation was invoked more than once",
                ))
            });
        }

        if let Some(layer) = self.layers.get(self.index).cloned() {
            let next = Self {
                layers: self.layers.clone(),
                index: self.index + 1,
                handler: self.handler.clone(),
                called: Arc::new(AtomicBool::new(false)),
            };
            let future =
                std::panic::catch_unwind(AssertUnwindSafe(|| layer.call(context, body, next)));
            return match future {
                Ok(future) => Box::pin(async move {
                    AssertUnwindSafe(future)
                        .catch_unwind()
                        .await
                        .unwrap_or_else(|_| Err(HandlerError::internal("middleware panicked")))
                }),
                Err(_) => Box::pin(async { Err(HandlerError::internal("middleware panicked")) }),
            };
        }

        let handler = self.handler.clone();
        let future = std::panic::catch_unwind(AssertUnwindSafe(|| handler(body)));
        match future {
            Ok(future) => Box::pin(async move {
                AssertUnwindSafe(future)
                    .catch_unwind()
                    .await
                    .unwrap_or_else(|_| Err(HandlerError::internal("handler panicked")))
            }),
            Err(_) => Box::pin(async { Err(HandlerError::internal("handler panicked")) }),
        }
    }
}

pub(crate) struct CancellationGuard {
    signal: RequestCancellation,
    completed: bool,
}

impl CancellationGuard {
    pub(crate) fn new(signal: RequestCancellation) -> Self {
        Self {
            signal,
            completed: false,
        }
    }

    pub(crate) fn complete(&mut self) {
        self.completed = true;
    }
}

impl Drop for CancellationGuard {
    fn drop(&mut self) {
        if !self.completed {
            self.signal.cancel();
        }
    }
}
