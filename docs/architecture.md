# Architecture decisions

## Product boundary

MoonLightBridge is a universal Java-to-Rust RPC runtime, not a remote implementation of the Bukkit
API. `moonlight-bridge-java` owns transport lifecycle for any Java 21+ process. The optional
`moonlight-bridge-paper` adapter owns Minecraft scheduler affinity, while Rust owns isolated
business logic, persistence, caches, and batchable computation.

## Performance rule

No design is accepted solely because it is theoretically fast. Each milestone
must retain an end-to-end benchmark measuring throughput, p50/p95/p99 latency,
CPU, allocations, and behavior under queue saturation.

The intended production shape is one batch per tick or per subsystem, not one
RPC per machine or Minecraft event.

## Initial choices

- TCP first because it is portable and easy to test; Unix sockets follow.
- A fixed binary envelope with opaque payloads.
- Multiplexing from the first implementation.
- Bounded payloads and explicit protocol errors.
- Every frontend—Java annotations, Kotlin/KSP, Rust attributes, or handwritten `.proto`—first
  produces a standard Protobuf descriptor. RPC bindings consume that one representation.
- Code-first declarations are ordinary language source; there is no MoonLightBridge-specific
  manifest or schema language.
- JNI and shared memory are out of scope until profiling justifies them.
- Multiple backends are explicit named channels; service clients never silently choose a route.
- Server streams use demand credits on the wire so backpressure remains bounded end to end.
- Metrics and traces are opt-in, application-owned, and propagated across the Java-to-Rust
  boundary with generated `Service/Method` names; the observability adapters do not own exporters
  or anonymous analytics.

## Reconnect safety

A disconnected request fails instead of being replayed automatically. The
client may reconnect, but retry policy belongs to the generated method contract:
read-only calls can opt in, while economy and inventory mutations require an
idempotency key. This prevents an unknown response state from duplicating an
operation. The Rust idempotency helper owns the operation independently from
the waiting RPC, so cancellation after an external commit cannot discard its
result and execute the same key again.
