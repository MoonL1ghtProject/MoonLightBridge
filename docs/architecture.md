# Architecture decisions

## Product boundary

MoonLightBridge is an RPC runtime, not a remote implementation of the Bukkit API. Java
owns Minecraft objects and scheduler affinity. Rust owns isolated business
logic, persistence, caches, and batchable computation.

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
- Protobuf schema/codegen after lifecycle semantics are stable.
- RPC bindings are generated from a standard Protobuf descriptor set rather
  than parsing `.proto` source independently in each language.
- JNI and shared memory are out of scope until profiling justifies them.

## Reconnect safety

A disconnected request fails instead of being replayed automatically. The
client may reconnect, but retry policy belongs to the generated method contract:
read-only calls can opt in, while economy and inventory mutations require an
idempotency key. This prevents an unknown response state from duplicating an
operation.
