# Observability

MoonLightBridge provides opt-in adapters for both sides of a Java-to-Rust call. All data belongs to
the application developer: these adapters do not send anonymous product analytics or select a
third-party destination. Java client spans propagate trace and parent span identifiers through
protocol metadata; the Rust server adapter continues the same trace. Generated calls use a
readable `Service/Method` span name instead of a method hash. Request IDs, method IDs, sizes,
status, exceptions, and backend stage timing stay available in traces for service developers.

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
the application. Prefer `tracer_provider_with_service(endpoint, service_name)` so the backend has
a stable `service.name` in Tempo.

Generated registrations pass the canonical method name to the server. Their decode, handler, and
encode instrumentation becomes child spans of the Rust request span; custom backend work wrapped
with `trace_function` or `trace_async_function` is recorded through the same request observation.

## Java, Kotlin, and Paper

`moonlight-bridge-micrometer` records request, connection, byte, latency, failure, and exporter-drop
meters in an application-owned `MeterRegistry`. `moonlight-bridge-otel` creates client spans and
wire trace context from an application-owned `OpenTelemetry` instance. `MoonLightOtlp` is an
optional bounded OTLP/HTTP lifecycle helper.

```java
var metrics = new MoonLightMicrometerTelemetry(registry);
var otlp = MoonLightOtlp.create(
    "http://localhost:4318/v1/traces",
    1024,
    "profile-paper"
);
var telemetry = MoonLightTelemetry.composite(metrics, otlp.telemetry());
var channel = MoonLightClient.connect(endpoint, performance, telemetry);
```

Paper and Folia plugins should expose metrics from their existing web/admin integration or a
plugin-owned server. The adapters never bind ports during class loading or construction.

The generated `RpcPolicy.methodName()` supplies the Java client span name. Raw calls without that
metadata retain the fallback `moonlight.rpc.<method-id>` name. A normal generated call appears as:

```text
profile-paper  ProfileService/LoadProfile
└─ profile-rust  ProfileService/LoadProfile
   ├─ protobuf.decode.ProfileService.LoadProfile
   ├─ handler.ProfileService.LoadProfile
   └─ protobuf.encode.ProfileService.LoadProfile
```

The Java span is the root, the Rust request span continues its propagated context, and the three
stage spans are children of the Rust request. Set a distinct explicit service name for every
process. Reusing one name makes separate runtimes difficult to distinguish; omitting it lets the
OpenTelemetry SDK fall back to an `unknown_service` resource.

Trace sampling determines whether spans are recorded. Metric collection is independent and uses
aggregate labels only. Neither adapter captures request or response bodies.

## Traces and profiling are different

Tempo displays the logical distributed span tree and timing across processes; it is not a sampled
CPU call stack. `MoonLightJfrTelemetry` can add bounded MoonLightBridge request events to an
application-controlled Java Flight Recorder recording, but those events deliberately omit stack
traces and payloads. Capture JVM or Rust CPU samples with the profiler appropriate to the deployed
process when a flame graph is required, then correlate the profile interval with trace timestamps
and request attributes. Profiling is never started by the public metrics or OpenTelemetry adapters.

## Runnable dashboard

[`examples/observability`](../examples/observability/README.md) generates the shared Protobuf
contract from annotated Rust source, then connects the Rust service and Java traffic generator to
Prometheus, Tempo, and a provisioned Grafana dashboard.
