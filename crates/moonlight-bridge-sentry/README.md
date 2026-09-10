# moonlight-bridge-sentry

Framework-owned Sentry telemetry for MoonLightBridge Rust backends. It connects propagated
Java trace context to Rust request spans, reports backend failures, and deliberately avoids
capturing request payloads.

```rust
let _guard = moonlight_bridge_sentry::init_framework_sentry();
```

Add `SentryMoonLightTelemetry` to the server's `TelemetryChain` when distributed tracing
is wanted. Sampling and debug behavior follow the framework's build/runtime telemetry
policy; application code does not need to own the framework DSN.

See the [observability guide](https://github.com/MoonL1ghtProject/MoonLightBridge/blob/main/docs/observability.md)
for privacy and sampling details. Licensed under MIT OR Apache-2.0.
