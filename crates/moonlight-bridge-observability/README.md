# moonlight-bridge-observability

Optional observability adapters for MoonLightBridge. The crate has no default features and never
starts an HTTP listener. Enable `prometheus`, `otel`, or `otlp` explicitly and attach the resulting
adapter to an application-owned registry or exporter lifecycle.

Metrics use stable aggregate names without per-request labels so Prometheus cardinality stays
bounded. Traces retain request correlation and propagated trace/span identifiers for complete
Java-to-Rust investigation. The Prometheus adapter consumes lock-free runtime snapshots and lets the
application expose the text format through its existing HTTP server.
