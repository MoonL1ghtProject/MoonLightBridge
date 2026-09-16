//! Primitives for safe retryable mutations and optimistic concurrency control.

use std::{
    collections::HashMap,
    future::Future,
    hash::Hash,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::{Mutex, Notify, OnceCell, RwLock};

struct Entry<V, E> {
    created: Instant,
    result: OnceCell<Result<V, E>>,
    ready: Notify,
}

/// A bounded TTL cache that coalesces concurrent calls carrying the same operation ID.
pub struct IdempotencyCache<K, V, E> {
    entries: Mutex<HashMap<K, Arc<Entry<V, E>>>>,
    capacity: usize,
    ttl: Duration,
}

#[derive(Debug, Clone, PartialEq, Eq)]
/// Cached operation result and whether it was reused by this caller.
pub struct Idempotent<V> {
    /// Shared operation result.
    pub value: V,
    /// True when this caller reused an existing in-flight or completed operation.
    pub replayed: bool,
}

impl<K, V, E> IdempotencyCache<K, V, E>
where
    K: Clone + Eq + Hash,
    V: Clone + Send + Sync + 'static,
    E: Clone + Send + Sync + 'static,
{
    /// Creates a process-local cache with a maximum entry count and retention time.
    pub fn new(capacity: usize, ttl: Duration) -> Self {
        assert!(capacity > 0, "idempotency cache capacity must be positive");
        assert!(!ttl.is_zero(), "idempotency cache TTL must be positive");
        Self {
            entries: Mutex::new(HashMap::new()),
            capacity,
            ttl,
        }
    }

    /// Executes `operation` once per live key and shares it with concurrent callers.
    ///
    /// The operation runs in an independent Tokio task, so cancelling the caller does not cancel a
    /// mutation that may already have committed an external side effect. Later callers with the
    /// same key continue waiting for and reuse that operation's result.
    pub async fn execute<F, Fut>(&self, key: K, operation: F) -> Result<Idempotent<V>, E>
    where
        F: FnOnce() -> Fut + Send + 'static,
        Fut: Future<Output = Result<V, E>> + Send + 'static,
    {
        let now = Instant::now();
        let (entry, replayed) = {
            let mut entries = self.entries.lock().await;
            // Never evict an in-flight operation: doing so could execute the same mutation twice.
            entries.retain(|_, entry| {
                entry.result.get().is_none() || now.duration_since(entry.created) < self.ttl
            });
            if let Some(entry) = entries.get(&key) {
                (entry.clone(), true)
            } else {
                if entries.len() >= self.capacity
                    && let Some(oldest) = entries
                        .iter()
                        .filter(|(_, entry)| entry.result.get().is_some())
                        .min_by_key(|(_, entry)| entry.created)
                        .map(|(key, _)| key.clone())
                {
                    entries.remove(&oldest);
                }
                let entry = Arc::new(Entry {
                    created: now,
                    result: OnceCell::new(),
                    ready: Notify::new(),
                });
                entries.insert(key, entry.clone());
                (entry, false)
            }
        };
        if !replayed {
            let worker_entry = entry.clone();
            tokio::spawn(async move {
                let result = operation().await;
                if worker_entry.result.set(result).is_ok() {
                    worker_entry.ready.notify_waiters();
                }
            });
        }

        loop {
            if let Some(result) = entry.result.get() {
                return result.clone().map(|value| Idempotent { value, replayed });
            }
            let notified = entry.ready.notified();
            if entry.result.get().is_none() {
                notified.await;
            }
        }
    }
}

/// A value protected by a monotonically increasing optimistic-concurrency revision.
pub struct Revisioned<T> {
    state: RwLock<RevisionState<T>>,
}

struct RevisionState<T> {
    revision: u64,
    value: T,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
/// Optimistic-concurrency failure caused by a stale expected revision.
pub struct RevisionConflict {
    /// Revision supplied by the caller.
    pub expected: u64,
    /// Current stored revision.
    pub actual: u64,
}

impl<T> Revisioned<T> {
    /// Wraps a value at initial revision zero.
    pub fn new(value: T) -> Self {
        Self {
            state: RwLock::new(RevisionState { revision: 0, value }),
        }
    }

    /// Returns the current revision and a clone of the value.
    pub async fn read(&self) -> (u64, T)
    where
        T: Clone,
    {
        let state = self.state.read().await;
        (state.revision, state.value.clone())
    }

    /// Applies an update only if `expected_revision` is still current.
    pub async fn update<R>(
        &self,
        expected_revision: u64,
        update: impl FnOnce(&mut T) -> R,
    ) -> Result<(u64, R), RevisionConflict> {
        let mut state = self.state.write().await;
        if state.revision != expected_revision {
            return Err(RevisionConflict {
                expected: expected_revision,
                actual: state.revision,
            });
        }
        let output = update(&mut state.value);
        state.revision = state
            .revision
            .checked_add(1)
            .expect("MoonLightBridge revision counter overflowed");
        Ok((state.revision, output))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[tokio::test]
    async fn replays_an_operation_only_once() {
        let cache = IdempotencyCache::new(8, Duration::from_secs(60));
        let calls = Arc::new(AtomicUsize::new(0));
        let first_calls = calls.clone();
        let first = cache
            .execute("operation", move || async move {
                first_calls.fetch_add(1, Ordering::Relaxed);
                Ok::<_, ()>(42)
            })
            .await
            .unwrap();
        let replay_calls = calls.clone();
        let replay = cache
            .execute("operation", move || async move {
                replay_calls.fetch_add(1, Ordering::Relaxed);
                Ok::<_, ()>(99)
            })
            .await
            .unwrap();
        assert_eq!(
            first,
            Idempotent {
                value: 42,
                replayed: false
            }
        );
        assert_eq!(
            replay,
            Idempotent {
                value: 42,
                replayed: true
            }
        );
        assert_eq!(calls.load(Ordering::Relaxed), 1);
    }

    #[tokio::test]
    async fn operation_survives_cancellation_of_its_first_waiter() {
        let cache = Arc::new(IdempotencyCache::new(8, Duration::from_secs(60)));
        let calls = Arc::new(AtomicUsize::new(0));
        let (started_tx, started_rx) = tokio::sync::oneshot::channel();
        let (complete_tx, complete_rx) = tokio::sync::oneshot::channel();

        let first_cache = cache.clone();
        let first_calls = calls.clone();
        let first = tokio::spawn(async move {
            first_cache
                .execute("operation", move || async move {
                    first_calls.fetch_add(1, Ordering::Relaxed);
                    let _ = started_tx.send(());
                    let _ = complete_rx.await;
                    Ok::<_, ()>(42)
                })
                .await
        });
        started_rx.await.unwrap();
        first.abort();

        let replay_cache = cache.clone();
        let replay_calls = calls.clone();
        let replay = tokio::spawn(async move {
            replay_cache
                .execute("operation", move || async move {
                    replay_calls.fetch_add(1, Ordering::Relaxed);
                    Ok::<_, ()>(99)
                })
                .await
        });
        complete_tx.send(()).unwrap();

        assert_eq!(replay.await.unwrap().unwrap().value, 42);
        assert_eq!(calls.load(Ordering::Relaxed), 1);
    }

    #[tokio::test]
    async fn revision_conflicts_do_not_mutate() {
        let value = Revisioned::new(10);
        assert_eq!(value.update(0, |value| *value += 1).await.unwrap().0, 1);
        assert_eq!(
            value
                .update(0, |value| *value += 10)
                .await
                .unwrap_err()
                .actual,
            1
        );
        assert_eq!(value.read().await, (1, 11));
    }
}
