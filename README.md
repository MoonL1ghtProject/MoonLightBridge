# expj

`expj` is a Minecraft-oriented RPC bridge between a Java plugin and a Rust
backend. The project is intentionally starting with a small, measurable core:
framing, multiplexed request/response, timeouts, and explicit failure modes.

## Current milestone: M3 embedded Minecraft SDK

The first vertical slice already defines the wire header and contains:

- a Rust Tokio server with concurrent request dispatch over TCP or Unix sockets;
- a dependency-free Java client using `CompletableFuture`;
- an echo backend used as an end-to-end integration test;
- protocol validation and payload size limits;
- capability handshake, request deadlines, cancellation and heartbeat;
- structured errors and bounded in-flight requests.
- Protobuf message generation, typed Java/Rust service bindings, and a checked
  compatibility lock.
- bounded dedicated writers, burst write coalescing, pooled temporary buffers,
  and generated batch RPC methods.
- opt-in metrics, payload-safe structured logs, and sampled Java-to-Rust Sentry
  trace propagation without overhead on the default path.
- a non-blocking embedded Paper/Folia facade with scheduler-aware callbacks and
  reconnect supervision; no separate bridge plugin is required.

## Run the vertical slice

Requirements: Rust/Cargo and JDK 21 or newer. The checked-in Gradle wrapper is
used for the Java build.

```bash
./scripts/integration-test.sh
```

Java selects a transport without changing the RPC API:

```java
var local = ExpjClient.connect("unix:/home/container/.expj/backend.sock");
var remote = ExpjClient.connect("tcp://backend.internal:38191");
var secure = ExpjClient.connect("tls://backend.example.com:38191");
```

Plugin authors depend on the single `dev.expj:expj-framework` artifact. Its
runtime Sentry provider is discovered automatically; plugin code does not
initialize or import Sentry. When producing a shaded Paper plugin, merge
`META-INF/services` entries so the provider remains discoverable.

Rust can listen with `Server::bind_unix(path, router)`,
`Server::bind_tcp(address, router).await`, or `Server::bind_tls(...)`. See
[`docs/deployment-pterodactyl.md`](docs/deployment-pterodactyl.md) for container
topologies and current security limits.

For a supervised connection that recovers after a backend restart without
replaying interrupted calls:

```java
var backend = ReconnectingExpjClient.connect("tls://backend.example.com:38191");
```

TLS endpoints require a trusted server certificate and a client certificate.
The Rust helper `load_mtls_server_config` builds a mandatory-client-auth rustls
configuration from PEM files.

The first schema-first API is generated from
[`proto/expj/example/v1/echo.proto`](proto/expj/example/v1/echo.proto). See
[`docs/codegen.md`](docs/codegen.md) for the generated client/service workflow.
Performance presets and measured baselines are documented in
[`docs/performance.md`](docs/performance.md).
Observability, Sentry sampling, privacy, and profiling are documented in
[`docs/observability.md`](docs/observability.md).
Paper/Folia integration is documented in
[`docs/minecraft-sdk.md`](docs/minecraft-sdk.md).

An installable Paper example and matching Rust backend live in
[`examples/paper-test-plugin`](examples/paper-test-plugin). The shaded plugin is
compiled to Java 21 bytecode and needs no separate EXPJ server plugin.

## Planned milestones

1. **M0 — framing:** request/response over TCP, multiplexing, limits.
2. **M1 — lifecycle:** handshake, deadlines, cancellation, heartbeat and safe reconnect (implemented).
3. **M2 — schema:** Protobuf contracts, unary Java/Rust code generation and compatibility lock (implemented); events follow.
4. **M3 — Minecraft SDK:** Paper/Folia scheduler-aware completion helpers (implemented).
5. **M4 — load control:** bounded queues, batching, tick batches, metrics (transport implemented; tick aggregation follows).
6. **M5 — backend process management:** extraction, startup and health supervision.

See [`docs/protocol.md`](docs/protocol.md) for the byte-level contract and
[`docs/architecture.md`](docs/architecture.md) for scope decisions.
