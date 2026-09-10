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
- generated protobuf encode/decode and backend-handler stage durations;
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

Official backend builds attach the framework-owned Sentry adapter internally. It is not a public
crate or an application integration API. Plugin/backend developers do not add it as a dependency,
initialize our Sentry client, supply our DSN, or control the framework sampling policy.

The public Rust API instead exposes vendor-neutral `Telemetry`, `RequestObservation`, and
`MoonLightMetrics` hooks. Applications can use them for local metrics or for their own independently
configured observability stack. Doing so neither grants access to nor changes the framework's
Sentry project.

## Build policy

The default build is the production policy and requires no flags:

- successful traces: 0.1%;
- successful request logs: disabled;
- Java CPU profiling: disabled;
- Sentry SDK debug output: disabled;
- errors: 100% on Java and Rust.

Diagnostic sampling and SDK debug settings are private release-pipeline controls, not public
runtime configuration. This prevents a plugin or server configuration from silently turning on
high-volume framework telemetry. Generated functions are timed automatically when their parent
request is sampled.

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
    new MoonLightJfrTelemetry()
);
```

Use JFR or async-profiler for Java allocation/CPU profiles and Linux `perf` plus
`cargo-flamegraph` for Rust. Record profiles under representative load with
Sentry tracing disabled first, then repeat at the intended sample rate to
measure observability overhead.

The checked-in defaults are the recommended production policy. Use a temporary
100% trace/profile build only during a controlled load test, and use JFR/Spark
or `perf` for longer profiling sessions.
