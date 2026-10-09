use crate::{HealthHandle, RuntimeState};
use std::{sync::Arc, time::Duration};

/// Cloneable control handle for readiness and graceful server draining.
#[derive(Clone)]
pub struct ServerHandle(pub(crate) Arc<RuntimeState>);

impl ServerHandle {
    pub(crate) fn new(runtime: Arc<RuntimeState>) -> Self {
        Self(runtime)
    }

    /// Returns a lock-free health handle for the same server runtime.
    pub fn health(&self) -> HealthHandle {
        HealthHandle(self.0.clone())
    }

    /// Stops admission and notifies every active connection exactly once.
    pub fn begin_drain(&self) -> bool {
        if self
            .0
            .draining
            .compare_exchange(
                false,
                true,
                std::sync::atomic::Ordering::AcqRel,
                std::sync::atomic::Ordering::Acquire,
            )
            .is_err()
        {
            return false;
        }
        self.0
            .ready
            .store(false, std::sync::atomic::Ordering::Release);
        let _ = self.0.drain_tx.send(true);
        self.0.state_changed.notify_waiters();
        true
    }

    /// Begins draining and waits until every connection and request exits or the timeout elapses.
    pub async fn drain(&self, timeout: Duration) -> bool {
        self.begin_drain();
        if tokio::time::timeout(timeout, async {
            loop {
                if self
                    .0
                    .active_connections
                    .load(std::sync::atomic::Ordering::Acquire)
                    == 0
                    && self
                        .0
                        .active_requests
                        .load(std::sync::atomic::Ordering::Acquire)
                        == 0
                {
                    return;
                }
                self.0.state_changed.notified().await;
            }
        })
        .await
        .is_ok()
        {
            true
        } else {
            let _ = self.0.force_drain_tx.send(true);
            self.0.state_changed.notify_waiters();
            false
        }
    }
}
