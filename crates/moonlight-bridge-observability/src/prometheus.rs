use moonlight_bridge_server::MetricsSnapshot;
use prometheus::{Encoder, IntCounter, IntGauge, Registry, TextEncoder};
use std::sync::Mutex;

/// Pull-based Prometheus adapter. The application decides where and whether to expose HTTP.
pub struct PrometheusSnapshotExporter {
    registry: Registry,
    started: IntCounter,
    succeeded: IntCounter,
    failed: IntCounter,
    request_bytes: IntCounter,
    response_bytes: IntCounter,
    latency_nanos: IntCounter,
    max_latency_nanos: IntGauge,
    exporter_drops: IntCounter,
    previous: Mutex<MetricsSnapshot>,
}

impl PrometheusSnapshotExporter {
    /// Creates an isolated registry without starting an HTTP listener or background thread.
    pub fn new() -> Result<Self, prometheus::Error> {
        let registry = Registry::new();
        let started = IntCounter::new(
            "moonlight_bridge_requests_started_total",
            "Started RPC requests",
        )?;
        let succeeded = IntCounter::new(
            "moonlight_bridge_requests_succeeded_total",
            "Successful RPC requests",
        )?;
        let failed = IntCounter::new(
            "moonlight_bridge_requests_failed_total",
            "Failed RPC requests",
        )?;
        let request_bytes = IntCounter::new(
            "moonlight_bridge_request_bytes_total",
            "Encoded request bytes",
        )?;
        let response_bytes = IntCounter::new(
            "moonlight_bridge_response_bytes_total",
            "Encoded response bytes",
        )?;
        let latency_nanos = IntCounter::new(
            "moonlight_bridge_request_latency_nanos_total",
            "Cumulative RPC latency",
        )?;
        let max_latency_nanos = IntGauge::new(
            "moonlight_bridge_request_latency_max_nanos",
            "Largest observed RPC latency",
        )?;
        let exporter_drops = IntCounter::new(
            "moonlight_bridge_exporter_drops_total",
            "Snapshots dropped because an exporter was busy",
        )?;
        registry.register(Box::new(started.clone()))?;
        registry.register(Box::new(succeeded.clone()))?;
        registry.register(Box::new(failed.clone()))?;
        registry.register(Box::new(request_bytes.clone()))?;
        registry.register(Box::new(response_bytes.clone()))?;
        registry.register(Box::new(latency_nanos.clone()))?;
        registry.register(Box::new(max_latency_nanos.clone()))?;
        registry.register(Box::new(exporter_drops.clone()))?;
        Ok(Self {
            registry,
            started,
            succeeded,
            failed,
            request_bytes,
            response_bytes,
            latency_nanos,
            max_latency_nanos,
            exporter_drops,
            previous: Mutex::new(MetricsSnapshot {
                started: 0,
                succeeded: 0,
                failed: 0,
                request_bytes: 0,
                response_bytes: 0,
                total_latency_nanos: 0,
                max_latency_nanos: 0,
            }),
        })
    }

    /// Applies one cumulative snapshot. Concurrent calls are dropped instead of blocking RPC work.
    pub fn try_update(&self, current: MetricsSnapshot) -> bool {
        let Ok(mut previous) = self.previous.try_lock() else {
            self.exporter_drops.inc();
            return false;
        };
        self.started
            .inc_by(current.started.saturating_sub(previous.started));
        self.succeeded
            .inc_by(current.succeeded.saturating_sub(previous.succeeded));
        self.failed
            .inc_by(current.failed.saturating_sub(previous.failed));
        self.request_bytes
            .inc_by(current.request_bytes.saturating_sub(previous.request_bytes));
        self.response_bytes.inc_by(
            current
                .response_bytes
                .saturating_sub(previous.response_bytes),
        );
        self.latency_nanos.inc_by(
            current
                .total_latency_nanos
                .saturating_sub(previous.total_latency_nanos),
        );
        self.max_latency_nanos.set(
            current
                .max_latency_nanos
                .min(i64::MAX as u64)
                .try_into()
                .unwrap_or(i64::MAX),
        );
        *previous = current;
        true
    }

    /// Encodes the current registry in Prometheus text exposition format.
    pub fn render(&self) -> Result<String, prometheus::Error> {
        let families = self.registry.gather();
        let mut output = Vec::new();
        TextEncoder::new().encode(&families, &mut output)?;
        Ok(String::from_utf8(output).expect("Prometheus text encoder emits UTF-8"))
    }

    /// Returns the isolated registry for integration with an existing metrics endpoint.
    pub fn registry(&self) -> &Registry {
        &self.registry
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exports_stable_low_cardinality_names_without_request_ids() {
        let exporter = PrometheusSnapshotExporter::new().unwrap();
        assert!(exporter.try_update(MetricsSnapshot {
            started: 3,
            succeeded: 2,
            failed: 1,
            request_bytes: 120,
            response_bytes: 80,
            total_latency_nanos: 900,
            max_latency_nanos: 500,
        }));
        let text = exporter.render().unwrap();
        assert!(
            text.contains("moonlight_bridge_requests_started_total 3"),
            "{text}"
        );
        assert!(
            text.contains("moonlight_bridge_requests_failed_total 1"),
            "{text}"
        );
        assert!(!text.contains("request_id"));
        assert!(!text.contains("logical_call_id"));
    }
}
