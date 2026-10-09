# Observability

MoonLightBridge provides opt-in adapters for both sides of a Java-to-Rust call. All data belongs to
the application developer: these adapters do not send anonymous product analytics or select a
third-party destination. Java client spans
propagate trace and parent span identifiers through protocol metadata; the Rust server adapter
continues the same trace. Request IDs, method IDs, sizes, status, exceptions, and backend stage
timing stay available in traces for service developers.

Prometheus labels deliberately remain aggregate and bounded. Request IDs belong in traces rather
than metric labels because every unique label set creates a time series and eventually exhausts
Prometheus memory, including in private installations.

## Rust

`moonlight-bridge-observability` has no default features. Select only the integrations the service
uses:

```toml
moonlight-bridge-observability = { version = "0.5.0", features = ["prometheus", "otel", "otlp"] }
```

`PrometheusSnapshotExporter` converts `MoonLightMetrics::snapshot()` into an isolated registry. It
does not open an HTTP listener. `OpenTelemetryTelemetry` plugs into `RouterBuilder::telemetry`, and
the optional OTLP helper creates a bounded background exporter whose provider must be shut down by
the application.

## Java, Kotlin, and Paper

`moonlight-bridge-micrometer` records request, connection, byte, latency, failure, and exporter-drop
meters in an application-owned `MeterRegistry`. `moonlight-bridge-otel` creates client spans and
wire trace context from an application-owned `OpenTelemetry` instance. `MoonLightOtlp` is an
optional bounded OTLP/HTTP lifecycle helper.

```java
var metrics = new MoonLightMicrometerTelemetry(registry);
var otlp = MoonLightOtlp.create("http://localhost:4318/v1/traces", 1024);
var telemetry = MoonLightTelemetry.composite(metrics, otlp.telemetry());
var channel = MoonLightClient.connect(endpoint, performance, telemetry);
```

Paper and Folia plugins should expose metrics from their existing web/admin integration or a
plugin-owned server. The adapters never bind ports during class loading or construction.

## Runnable dashboard

[`examples/observability`](../examples/observability/README.md) generates the shared Protobuf
contract from annotated Rust source, then connects the Rust service and Java traffic generator to
Prometheus, Tempo, and a provisioned Grafana dashboard.
