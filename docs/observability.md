# Observability and profiling

MoonLightBridge records transport health and performance without requiring application setup. The
published Java runtime discovers the framework-owned provider automatically; application code does
not initialize it, supply credentials, or depend on an observability SDK.

## What is recorded

- stable method and connection-local request IDs;
- request and response byte counts;
- duration and success/error status;
- generated Protobuf encode/decode and backend-handler stage durations;
- Java exceptions and Rust `INTERNAL` handler failures.

Request and response bodies are never logged or attached. Player names, chat, inventory contents,
coordinates, and application payloads therefore remain outside framework telemetry.

Production builds record every error and only a small sample of successful traces. Successful
request logs, remote CPU profiling, and SDK debug output are disabled. These are build-time project
controls rather than plugin configuration, so an application cannot silently enable high-volume
framework telemetry.

## Application metrics

`MoonLightClientMetrics.snapshot()` and Rust `MoonLightMetrics::snapshot()` expose request counts,
failures, byte totals, total latency, and maximum latency. They use adders or relaxed atomics and do
not perform network I/O.

```java
var metrics = new MoonLightClientMetrics();
var telemetry = MoonLightTelemetry.composite(metrics, new MoonLightJfrTelemetry());
var client = MoonLightClient.connect(endpoint, performance, telemetry);
var snapshot = metrics.snapshot();
```

The high-level runtime deliberately hides framework-owned telemetry configuration. The explicit SPI
above is for advanced low-level integrations that want local metrics or JFR events.

## Profiling

`MoonLightJfrTelemetry` emits `ru.moonlightproject.bridge.RpcRequest` events during a Java Flight
Recorder recording. Use JFR or async-profiler for Java CPU/allocation profiles. Use Linux `perf`,
`cargo-flamegraph`, or another Rust-compatible profiler for the backend.

Generated codec and handler stages appear as named functions when their parent request is sampled.
Java code can use `MoonLightTelemetry.traceFunction(name, operation)` and Rust code can use
`trace_function` or `trace_async_function` around a meaningful slow stage. Avoid one span per loop
iteration or Minecraft object; instrument domain-level stages.

Record profiles under representative load and compare them with telemetry disabled before drawing
performance conclusions. Paper/Folia scheduler delay occurs after the RPC future completes and
should be measured separately from bridge transport latency.
