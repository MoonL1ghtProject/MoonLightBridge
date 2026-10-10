# Changelog

MoonLightBridge follows [Semantic Versioning](https://semver.org/). This file records
user-visible framework changes; low-level refactors stay in the Git history.

## Unreleased

### 0.5.0

- Added wire protocol v2 with bounded typed metadata, strict canonical decoding, structured
  authentication/availability errors, negotiated Zstandard compression, and decoded-byte budgets.
- Added generated RPC policies for deadlines, retry safety, message limits, authorization scopes,
  compression preferences, and deterministic trace sampling.
- Added client/server interceptor chains, request contexts, and graceful connection draining.
- Added code-first Protobuf generation from annotated Java, native Kotlin/KSP declarations, and
  Rust source attributes while retaining optional handwritten `.proto` input.
- Preserved schema field/enum numbers and emitted reservations for removed code-first members using
  the compatibility lock; verified equivalent Java, Kotlin, and Rust contracts as descriptors.
- Added application-owned Micrometer/Prometheus metrics and OpenTelemetry/OTLP trace propagation so
  a Java or Paper client span continues through Rust handler stages.
- Generated spans now use readable `Service/Method` operation names. OTLP helpers accept an
  explicit `service.name`, and Rust exports Protobuf decode, application handler, and Protobuf
  encode work as child spans under the server request span.
- Added the Grafana, Prometheus, and Tempo observability example and expanded the Paper example to
  cover unary, batch, event, server-streaming, and drain behavior.
- Reduced protocol-v2 Java request-path allocations by encoding runtime metadata directly into the
  pooled frame and bypassing interceptor context construction when no interceptors are registered.
- Added allocation-free borrowed Rust metadata validation and deferred owned request contexts until
  middleware actually needs them.

Wire protocol v2 has no v1 fallback. Deploy compatible 0.5 clients and servers together. Public
0.5.0 artifacts are not available until the corresponding release tag is published.

## 0.4.0 — Resource limits and lifecycle hardening

- Bounded server connection admission, frame memory and I/O lifetimes; made blocked-writer shutdown finite.
- Made idempotency capacity strict, retained results from completion time, and surfaced overload/indeterminate operation outcomes without replaying uncertain mutations.
- Rejected server handshake settings that exceed client limits or advertise unrequested features.
- Isolated callback failures, serialized stream completion, and cancelled completed-stream timers.
- Bounded pooled/outgoing buffer memory and prevented expired queued calls from being written.
- Preserved cancellation through generated typed calls and batches.
- Removed raw exception content from embedded telemetry; added runtime opt-out and destination override.
- Deferred automatic telemetry discovery to the reconnect supervisor so asynchronous startup remains non-blocking.
- Updated rustls to 0.23.45 and replaced the unmaintained rustls-pemfile wrapper.
- Added transport/security regression checks and fixed backend readiness in integration tests.

This minor release changes the Rust idempotency result error type to represent overload and uncertain outcomes explicitly. Regenerate typed Java clients to obtain cancellation propagation. Wire protocol version 1 is unchanged.

## 0.3.0 — Streaming and multi-backend channels

- Added negotiated, credit-based server-streaming RPCs with generated Rust and Java APIs.
- Added `MoonLightBridgeGroup` for explicitly named independent backend connections.
- Made connection reads cancellation-safe when server events are published concurrently.
- Made idempotent operations survive cancellation of the request that started them.
- Enforced the negotiated body limit for every outbound server response.
- Rejected reuse of message and enum reservations recorded in a schema lock.

## 0.2.2 — Publication metadata fix

- Restored the `sources` artifact on the relocated universal Java publication.
- Added a release-time assertion for the main, sources, and Javadoc classifiers.

## 0.2.1 — Private runtime packaging

- Embedded and relocated remote instrumentation inside `moonlight-bridge-java`.
- Removed the private provider from Maven, GitHub Packages, release assets, and public guides.
- Added a packaged-runtime test and completed high-level lifecycle Javadocs.

## 0.2.0 — Universal Java runtime

- Added `moonlight-bridge-java`, the recommended Java 21+ lifecycle facade without Bukkit dependencies.
- Rebuilt `moonlight-bridge-paper` as a thin scheduler-safe adapter over the universal runtime.
- Kept `moonlight-bridge-framework` as a compatibility aggregate for existing 0.1.x consumers.
- Made Maven Central the primary Java source and retained GitHub Packages as a mirror.
- Expanded Java guides, generated-client Javadocs, crates.io READMEs, and strict public Rust API documentation.

## 0.1.1 — Rust documentation

- Expanded crates.io and docs.rs documentation for the server, code generator, and wire protocol.
- Added checked examples for transports, routing, schema locks, errors, metrics, events, and safe mutations.
- Made the framework-owned remote telemetry adapter private and ended its separate publication.
- Separated optional Maven Central publishing from crates.io, GitHub Packages, and GitHub Releases.

## 0.1.0 — first public release

- Added multiplexed Java-to-Rust RPC over TCP, Unix sockets and mutual TLS.
- Added generated Protobuf Java clients, Rust services, typed batches and server events.
- Added bounded dedicated writers, burst coalescing, buffer reuse and explicit backpressure.
- Added deadlines, cancellation, heartbeat, reconnect supervision and health/readiness.
- Added Paper/Folia scheduler-aware completions without a separate bridge server plugin.
- Added schema compatibility locking, duplicate method/request protection and strict response validation.
- Added framework-owned error reporting, propagated traces, metrics and Java Flight Recorder events.
- Added Maven Central, GitHub Packages, crates.io and checksummed GitHub Release automation.
