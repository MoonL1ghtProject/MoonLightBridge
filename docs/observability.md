# Observability and Sentry

The distributable `moonlight-bridge-framework` library owns its Sentry configuration and
pulls in `moonlight-bridge-sentry` at runtime. Java discovers it through `ServiceLoader` and
ordinary `MoonLightClient.connect(...)` enables framework telemetry automatically.
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

Depend on `moonlight-bridge-framework`, ensure the plugin's Shadow configuration merges
service files, and connect normally:

```java
var backend = MoonLightClient.connect("unix:/run/moonlight-bridge/backend.sock");
```

Ordinary builds sample 0.1% of successful RPCs. Runtime system properties and
plugin configuration cannot increase this rate: the framework policy is
embedded when the library/plugin is built. The span's 16-byte trace ID,
8-byte parent span ID, and sampling bit are propagated to Rust only for sampled
traces. Unsampled requests keep the normal wire path.

Unexpected connection termination is captured once as a Java Sentry error;
ordinary client shutdown is only a breadcrumb. Every RPC failure is captured
as an error even when its trace was not sampled. Rust follows the same rule.

## Rust server

The Rust runtime calls `init_framework_sentry()` internally and installs a
target-filtered `tracing` layer. Only MoonLightBridge crate targets are accepted;
unrelated application logs are ignored. The server composes the adapter with
local counters:

```rust
use moonlight_bridge_sentry::SentryMoonLightTelemetry;
use moonlight_bridge_server::{MoonLightMetrics, Router, Telemetry, TelemetryChain};
use std::sync::Arc;

let metrics = MoonLightMetrics::default();
let telemetry = TelemetryChain::new([
    Arc::new(metrics.clone()) as Arc<dyn Telemetry>,
    Arc::new(SentryMoonLightTelemetry) as Arc<dyn Telemetry>,
]);

let router = Router::builder()
    .telemetry(Arc::new(telemetry))
    .route(1, |body| async move { Ok(body) })
    .build();
```

The `moonlight-bridge-sentry` crate re-exports `sentry_tracing`; applications own global
subscriber setup so MoonLightBridge never replaces an existing logger. Configure the
official layer with the shared filter so every backend uses the same policy:

```rust
tracing_subscriber::registry()
    .with(
        moonlight_bridge_sentry::sentry_tracing::layer()
            .event_filter(moonlight_bridge_sentry::framework_event_filter),
    )
    .init();
```

`SentryMoonLightTelemetry` emits payload-free request errors directly. Routine
completion logs are compiled out by default. The subscriber layer handles
additional framework `WARN`/`ERROR` records as logs without creating a second
issue for an RPC error.

## Build policy

The default build is the production policy and requires no flags:

- successful traces: 0.1%;
- successful request logs: disabled;
- Java CPU profiling: disabled;
- Sentry SDK debug output: disabled;
- errors: 100% on Java and Rust.

For a controlled diagnostic build, framework maintainers can embed a different
Java policy with internal Gradle properties:

```bash
./gradlew \
  -PmoonlightBridge.internal.telemetry.traceSampleRate=1.0 \
  -PmoonlightBridge.internal.telemetry.profileSampleRate=1.0 \
  -PmoonlightBridge.internal.telemetry.successLogs=true \
  -PmoonlightBridge.internal.telemetry.debug=true \
  :examples:paper-test-plugin:shadowJar
```

These values are copied into
`META-INF/moonlight-bridge/telemetry.properties` inside the artifact. They are
intentionally not part of the public Java API. Rebuild the plugin without the
flags before shipping it.

The Rust adapter inherits Java's propagated trace sampling decision. Its
routine success logs can be enabled only while compiling a diagnostic backend:

```bash
MOONLIGHT_BRIDGE_INTERNAL_TELEMETRY_SUCCESS_LOGS=true cargo build --release
```

`MOONLIGHT_BRIDGE_INTERNAL_TELEMETRY_ENVIRONMENT` and
`MOONLIGHT_BRIDGE_INTERNAL_TELEMETRY_RELEASE` may likewise be embedded by the
framework's release pipeline. Runtime environment variables do not change an
already built binary.

## Metrics and profiling

`MoonLightClientMetrics.snapshot()` and `MoonLightMetrics.snapshot()` expose
request counts, failures, byte totals, total latency, and maximum latency. They
use adders/relaxed atomics and do not perform network I/O.

The Java adapter includes Sentry's async-profiler integration on Linux and
macOS. Profiling is disabled in ordinary artifacts to avoid permanent CPU
overhead and can be enabled only in a diagnostic build. Sentry transaction
completion and log submission run on a dedicated bounded telemetry executor,
not the MoonLightBridge response-reader thread. Profiles use trace lifecycle, so they are
associated with MoonLightBridge RPC traces in Sentry. Windows is not supported by Sentry's
async-profiler integration. The Sentry transport queue is bounded at 256 items;
it is independent from MoonLightBridge's RPC backpressure queues.

The Rust Sentry SDK supports distributed tracing but has no native Sentry CPU
profiler. Use `perf`, `cargo-flamegraph`, or a Rust-compatible profiler for the
backend. MoonLightBridge also ships an optional `MoonLightJfrTelemetry` that emits
`ru.moonlightproject.bridge.RpcRequest` events during a JFR recording:

```java
var telemetry = MoonLightTelemetry.composite(
    metrics,
    new MoonLightJfrTelemetry(),
    new SentryMoonLightTelemetry()
);
```

Use JFR or async-profiler for Java allocation/CPU profiles and Linux `perf` plus
`cargo-flamegraph` for Rust. Record profiles under representative load with
Sentry tracing disabled first, then repeat at the intended sample rate to
measure observability overhead.

The checked-in defaults are the recommended production policy. Use a temporary
100% trace/profile build only during a controlled load test, and use JFR/Spark
or `perf` for longer profiling sessions.
