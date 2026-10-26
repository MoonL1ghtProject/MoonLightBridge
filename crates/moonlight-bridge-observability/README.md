# moonlight-bridge-observability

Optional observability adapters for MoonLightBridge. The crate has no default features and never
starts an HTTP listener. Enable `prometheus`, `otel`, or `otlp` explicitly and attach the resulting
adapter to an application-owned registry or exporter lifecycle.

The adapters do not send anonymous product analytics or configure a MoonLightBridge-owned
destination. The service developer chooses the endpoint, retention, and access policy.

Metrics use stable aggregate names without per-request labels so Prometheus cardinality stays
bounded. Traces retain request correlation and propagated trace/span identifiers for complete
Java-to-Rust investigation. The Prometheus adapter consumes lock-free runtime snapshots and lets the
application expose the text format through its existing HTTP server.

Use `otlp::tracer_provider_with_service` when this crate owns the OTLP exporter lifecycle:

```rust,no_run
# // Requires the `otel` and `otlp` features.
# #[cfg(all(feature = "otel", feature = "otlp"))]
# fn build() -> Result<(), Box<dyn std::error::Error>> {
use moonlight_bridge_observability::{otel::OpenTelemetryTelemetry, otlp};
use opentelemetry::trace::TracerProvider as _;

let provider = otlp::tracer_provider_with_service(
    "http://127.0.0.1:4318/v1/traces",
    "profile-rust",
)?;
let telemetry = OpenTelemetryTelemetry::new(provider.tracer("moonlight-bridge"));
// Pass `telemetry` to `Router::builder().telemetry(...)` and retain `provider`
// until shutdown so its bounded batch exporter can flush.
# let _ = telemetry;
# Ok(())
# }
```

Generated routes use `Service/Method` for the server request span. With a propagated Java client
context, Tempo shows one Java root span and one Rust request child. Under the Rust span, every stage
sits at its real offset: `moonlight.server.queue`, Protobuf decode, handler execution, Protobuf
encode, and `moonlight.server.write` (framework stages only for sampled requests). Raw routes without generated naming metadata fall
back to `moonlight.rpc.<method-id>`.
