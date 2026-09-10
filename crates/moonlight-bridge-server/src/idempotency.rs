//! Primitives for safe retryable mutations and optimistic concurrency control.

use std::{
    collections::HashMap,
    future::Future,
    hash::Hash,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::{Mutex, OnceCell, RwLock};

struct Entry<V, E> {
    created: Instant,
    result: OnceCell<Result<V, E>>,
}

/// A bounded TTL cache that coalesces concurrent calls carrying the same operation ID.
pub struct IdempotencyCache<K, V, E> {
    entries: Mutex<HashMap<K, Arc<Entry<V, E>>>>,
    capacity: usize,
    ttl: Duration,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Idempotent<V> {
    pub value: V,
    /// True when this caller reused an existing in-flight or completed operation.
    pub replayed: bool,
}

impl<K, V, E> IdempotencyCache<K, V, E>
where
    K: Clone + Eq + Hash,
    V: Clone,
    E: Clone,
{
    pub fn new(capacity: usize, ttl: Duration) -> Self {
        assert!(capacity > 0, "idempotency cache capacity must be positive");
        assert!(!ttl.is_zero(), "idempotency cache TTL must be positive");
        Self {
            entries: Mutex::new(HashMap::new()),
            capacity,
            ttl,
        }
    }

    pub async fn execute<F, Fut>(&self, key: K, operation: F) -> Result<Idempotent<V>, E>
    where
        F: FnOnce() -> Fut,
        Fut: Future<Output = Result<V, E>>,
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
                });
                entries.insert(key, entry.clone());
                (entry, false)
            }
        };
        let result = entry.result.get_or_init(operation).await.clone();
        result.map(|value| Idempotent { value, replayed })
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
pub struct RevisionConflict {
    pub expected: u64,
    pub actual: u64,
}

impl<T> Revisioned<T> {
    pub fn new(value: T) -> Self {
        Self {
            state: RwLock::new(RevisionState { revision: 0, value }),
        }
    }

    pub async fn read(&self) -> (u64, T)
    where
        T: Clone,
    {
        let state = self.state.read().await;
        (state.revision, state.value.clone())
    }

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
        let calls = AtomicUsize::new(0);
        let first = cache
            .execute("operation", || async {
                calls.fetch_add(1, Ordering::Relaxed);
                Ok::<_, ()>(42)
            })
            .await
            .unwrap();
        let replay = cache
            .execute("operation", || async {
                calls.fetch_add(1, Ordering::Relaxed);
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
