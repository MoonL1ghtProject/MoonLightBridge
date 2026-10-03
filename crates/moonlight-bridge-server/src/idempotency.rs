//! Primitives for safe retryable mutations and optimistic concurrency control.

use futures_util::FutureExt;
use std::{
    collections::{HashMap, VecDeque},
    future::Future,
    hash::Hash,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::{Mutex, Notify, OnceCell, RwLock};

struct Entry<V, E> {
    result: OnceCell<(Instant, Result<V, IdempotencyError<E>>)>,
    ready: Notify,
}

struct CacheState<K, V, E> {
    entries: HashMap<K, Arc<Entry<V, E>>>,
    completed: VecDeque<K>,
}

/// A strictly bounded process-local cache that coalesces calls with the same operation ID.
/// Completed operations expire relative to completion and may be evicted under capacity pressure.
/// Indeterminate operations remain pinned for the cache lifetime: reconcile them externally before
/// replacing the cache. This is not a durable exactly-once guarantee.
pub struct IdempotencyCache<K, V, E> {
    state: Arc<Mutex<CacheState<K, V, E>>>,
    capacity: usize,
    ttl: Duration,
}

/// Failure of cache admission or of the shared operation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum IdempotencyError<E> {
    /// The operation returned an application error.
    Operation(E),
    /// All cache slots are occupied by pending or indeterminate operations.
    Overloaded,
    /// The worker panicked; an external side effect may already have committed.
    Indeterminate,
}

impl<E: std::fmt::Display> std::fmt::Display for IdempotencyError<E> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Operation(error) => write!(f, "operation failed: {error}"),
            Self::Overloaded => f.write_str("idempotency cache is full"),
            Self::Indeterminate => f.write_str("operation outcome is indeterminate"),
        }
    }
}
impl<E: std::error::Error + 'static> std::error::Error for IdempotencyError<E> {}

#[derive(Debug, Clone, PartialEq, Eq)]
/// Cached operation result and whether it was reused by this caller.
pub struct Idempotent<V> {
    /// Shared operation result.
    pub value: V,
    /// True when this caller reused an in-flight or completed operation.
    pub replayed: bool,
}

impl<K, V, E> IdempotencyCache<K, V, E>
where
    K: Clone + Eq + Hash + Send + 'static,
    V: Clone + Send + Sync + 'static,
    E: Clone + Send + Sync + 'static,
{
    /// Creates a process-local cache with a maximum entry count and retention time.
    pub fn new(capacity: usize, ttl: Duration) -> Self {
        assert!(capacity > 0, "idempotency cache capacity must be positive");
        assert!(!ttl.is_zero(), "idempotency cache TTL must be positive");
        Self {
            state: Arc::new(Mutex::new(CacheState {
                entries: HashMap::new(),
                completed: VecDeque::new(),
            })),
            capacity,
            ttl,
        }
    }

    /// Shares one independent operation with all callers using its key.
    /// Cancelling a waiter does not cancel its operation. Panics yield a pinned indeterminate
    /// outcome and never automatically replay a potentially committed mutation.
    pub async fn execute<F, Fut>(
        &self,
        key: K,
        operation: F,
    ) -> Result<Idempotent<V>, IdempotencyError<E>>
    where
        F: FnOnce() -> Fut + Send + 'static,
        Fut: Future<Output = Result<V, E>> + Send + 'static,
    {
        let (entry, replayed) = {
            let mut state = self.state.lock().await;
            // Fast hits do not scan the cache. Expiry and eviction use completion order.
            if let Some(entry) = state.entries.get(&key) {
                let live = entry.result.get().is_none_or(|(completed, result)| {
                    matches!(result, Err(IdempotencyError::Indeterminate))
                        || completed.elapsed() < self.ttl
                });
                if live {
                    (entry.clone(), true)
                } else {
                    // Remove completed entries up to this expired key in completion order.
                    while let Some(oldest) = state.completed.pop_front() {
                        state.entries.remove(&oldest);
                        if oldest == key {
                            break;
                        }
                    }
                    let entry = Arc::new(Entry {
                        result: OnceCell::new(),
                        ready: Notify::new(),
                    });
                    state.entries.insert(key.clone(), entry.clone());
                    (entry, false)
                }
            } else {
                if state.entries.len() >= self.capacity {
                    let mut evicted = false;
                    while let Some(oldest) = state.completed.pop_front() {
                        if state.entries.remove(&oldest).is_some() {
                            evicted = true;
                            break;
                        }
                    }
                    if !evicted {
                        return Err(IdempotencyError::Overloaded);
                    }
                }
                let entry = Arc::new(Entry {
                    result: OnceCell::new(),
                    ready: Notify::new(),
                });
                state.entries.insert(key.clone(), entry.clone());
                (entry, false)
            }
        };
        if !replayed {
            let worker_entry = entry.clone();
            let state = self.state.clone();
            tokio::spawn(async move {
                // Include closure invocation in the unwind boundary, not only future polling.
                let result = std::panic::AssertUnwindSafe(async move { operation().await })
                    .catch_unwind()
                    .await
                    .map_or(Err(IdempotencyError::Indeterminate), |result| {
                        result.map_err(IdempotencyError::Operation)
                    });
                let mut state = state.lock().await;
                let determinate = !matches!(result, Err(IdempotencyError::Indeterminate));
                let _ = worker_entry.result.set((Instant::now(), result));
                if determinate {
                    state.completed.push_back(key);
                }
                worker_entry.ready.notify_waiters();
            });
        }
        loop {
            // Register before inspecting state to avoid losing a completion notification.
            let notified = entry.ready.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            if let Some((_, result)) = entry.result.get() {
                return result.clone().map(|value| Idempotent { value, replayed });
            }
            notified.await;
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
    async fn pending_capacity_is_strict() {
        let cache = Arc::new(IdempotencyCache::new(1, Duration::from_secs(1)));
        let (started, ready) = tokio::sync::oneshot::channel();
        let other = cache.clone();
        let first = tokio::spawn(async move {
            other
                .execute(1, || async move {
                    let _ = started.send(());
                    std::future::pending::<Result<(), ()>>().await
                })
                .await
        });
        ready.await.unwrap();
        assert_eq!(
            cache.execute(2, || async { Ok(()) }).await,
            Err(IdempotencyError::Overloaded)
        );
        first.abort();
    }

    #[tokio::test]
    async fn retention_starts_at_completion() {
        let cache = IdempotencyCache::new(1, Duration::from_millis(30));
        cache
            .execute(1, || async {
                tokio::time::sleep(Duration::from_millis(60)).await;
                Ok::<_, ()>(42)
            })
            .await
            .unwrap();
        assert_eq!(
            cache.execute(1, || async { Ok(99) }).await.unwrap().value,
            42
        );
    }

    #[tokio::test]
    async fn panic_is_terminal_and_never_reexecuted() {
        let cache = IdempotencyCache::<_, (), ()>::new(1, Duration::from_millis(10));
        let outcome = tokio::time::timeout(
            Duration::from_millis(100),
            cache.execute(1, || async { panic!("possible committed side effect") }),
        )
        .await;
        assert!(outcome.is_ok(), "panicking worker must wake waiters");
        assert_eq!(outcome.unwrap(), Err(IdempotencyError::Indeterminate));
        tokio::time::sleep(Duration::from_millis(20)).await;
        assert_eq!(
            cache.execute(1, || async { Ok(()) }).await,
            Err(IdempotencyError::Indeterminate)
        );
    }

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
