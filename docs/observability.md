# Observability and Sentry

The distributable `expj-framework` library owns its Sentry configuration and
pulls in `expj-sentry` at runtime. Java discovers it through `ServiceLoader` and
ordinary `ExpjClient.connect(...)` enables framework telemetry automatically.
Plugin code does not import or initialize Sentry.

Java client telemetry is sent to the framework's Java Sentry project, while the
Rust adapter uses its Rust project in the same Sentry organization. Propagated
trace IDs let Sentry join both projects into one distributed trace, and keeping
the platform-specific projects separate allows Java CPU profiles to be indexed
and displayed correctly.

## What is recorded

- stable method ID and connection-local request ID;
- request/response byte counts;
- duration and success/error status;
- Java exceptions and Rust `INTERNAL` handler failures.

Request and response bodies are never logged or attached to Sentry. Player
names, chat, inventory contents, coordinates, and other plugin data therefore
remain outside the telemetry adapter unless an application explicitly records
them itself.

## Java client

Depend on `expj-framework`, ensure the plugin's Shadow configuration merges
service files, and connect normally:

```java
var backend = ExpjClient.connect("unix:/run/expj/backend.sock");
```

Snapshot builds sample traces at 10% so integration testing remains visible
without instrumenting every RPC. Release builds default to 1%, so an unsampled background machine loop
does not create a Sentry transaction per RPC. Override either policy with the
`expj.sentry.trace-sample-rate` system property. The span's 16-byte trace ID,
8-byte parent span ID, and sampling bit are propagated to Rust only for sampled
traces. Unsampled requests keep the normal wire path.

Unexpected connection termination is captured once as a Java Sentry error;
ordinary client shutdown is only a breadcrumb. Rust `INTERNAL` failures are
emitted as `ERROR` records and are captured by the configured
`sentry_tracing` layer, avoiding one duplicate event per pending Java request.

## Rust server

The Rust runtime calls `init_framework_sentry()` internally and installs a
target-filtered `tracing` layer. Only targets beginning with `expj` are accepted;
unrelated application logs are ignored. The server composes the adapter with
local counters:

```rust
use expj_sentry::SentryExpjTelemetry;
use expj_server::{ExpjMetrics, Router, Telemetry, TelemetryChain};
use std::sync::Arc;

let metrics = ExpjMetrics::default();
let telemetry = TelemetryChain::new([
    Arc::new(metrics.clone()) as Arc<dyn Telemetry>,
    Arc::new(SentryExpjTelemetry) as Arc<dyn Telemetry>,
]);

let router = Router::builder()
    .telemetry(Arc::new(telemetry))
    .route(1, |body| async move { Ok(body) })
    .build();
```

The `expj-sentry` crate re-exports `sentry_tracing`; applications own global
subscriber setup so EXPJ never replaces an existing logger. Configure the
official layer with the shared filter so every backend uses the same policy:

```rust
tracing_subscriber::registry()
    .with(
        expj_sentry::sentry_tracing::layer()
            .event_filter(expj_sentry::framework_event_filter),
    )
    .init();
```

`SentryExpjTelemetry` emits payload-free request completion/failure logs
directly. These logs do not depend on the global subscriber, the presence of a
propagated trace context, or the trace sampling decision. The subscriber layer
handles additional framework `WARN` records and captures `ERROR` records.

## Metrics and profiling

`ExpjClientMetrics.snapshot()` and `ExpjMetrics.snapshot()` expose request
counts, failures, byte totals, total latency, and maximum latency. They use
adders/relaxed atomics and do not perform network I/O.

The Java adapter includes Sentry's async-profiler integration on Linux and
macOS. Snapshot builds profile 1% of traces by default; release builds leave
profiling disabled to avoid permanent CPU overhead. Sentry transaction
completion and log submission run on a dedicated bounded telemetry executor,
not the EXPJ response-reader thread. Set
`-Dexpj.sentry.profile-sample-rate=0.01` (range `0.0` to `1.0`) to opt a
production process into short profiling sessions. Profiles use trace lifecycle,
so they are associated with EXPJ RPC traces in Sentry. Windows is not supported
by Sentry's async-profiler integration.

Snapshot builds also send payload-free request completion logs. Release builds
disable routine Java logs by default; use `-Dexpj.sentry.logs=true` when
diagnosing a deployment. Payloads are excluded in both modes. Logging and trace
sampling are independent: setting `-Dexpj.sentry.trace-sample-rate=0` no longer
disables Logs. The Sentry transport queue is bounded at 4096 items for snapshot
burst tests and 256 for sampled release telemetry; it is independent from
EXPJ's RPC backpressure queues.

The Rust Sentry SDK supports distributed tracing but has no native Sentry CPU
profiler. Use `perf`, `cargo-flamegraph`, or a Rust-compatible profiler for the
backend. EXPJ also ships an optional `ExpjJfrTelemetry` that emits
`dev.expj.RpcRequest` events during a JFR recording:

```java
var telemetry = ExpjTelemetry.composite(
    metrics,
    new ExpjJfrTelemetry(),
    new SentryExpjTelemetry()
);
```

Use JFR or async-profiler for Java allocation/CPU profiles and Linux `perf` plus
`cargo-flamegraph` for Rust. Record profiles under representative load with
Sentry tracing disabled first, then repeat at the intended sample rate to
measure observability overhead.

Recommended starting policy:

- production traces: 0.1-1%;
- errors: 100%, with rate limits in Sentry;
- `TRACE` wire logs: off in production;
- payload capture and default PII: always off;
- short profiling sessions on a replica or during controlled load tests.
