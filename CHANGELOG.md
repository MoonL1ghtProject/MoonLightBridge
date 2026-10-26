# Changelog

MoonLightBridge follows [Semantic Versioning](https://semver.org/). This file records
user-visible framework changes; low-level refactors stay in the Git history.

## Unreleased

- Generated Rust service files no longer conflict with an application's own imports of
  `HandlerError`, `Arc`, `EventHub`, and similar names. Their dependencies now come from a hidden
  glob-imported module, which an explicit `use` shadows. Code relying on those names from the
  generated file keeps compiling.
- Documented how to include generated Rust models and services, including APIs that span several
  Protobuf packages, and listed `moonlight-bridge-protocol` among the required dependencies.

## 0.6.1 — Code-first fix, published examples, faster releases

- Fixed code-first contracts in packages with a `moonlight` segment that is not first, such as
  `dev.moonlight.app.v1`: the Java, Kotlin, and Rust generators now write the fully qualified
  `(.moonlight.bridge.options.v1.rpc_policy)` option, which `protoc` previously resolved to a
  missing `dev.moonlight.bridge...` name.
- Examples now consume MoonLightBridge exactly like applications do: the published Maven Central
  artifacts, the published `ru.moonlightproject.bridge` Gradle plugin, and the published crates
  and `moonlight-bridge-codegen` (`publishedMoonLightBridgeVersion` in `gradle.properties`). They
  share the `examples/contract` Echo schema instead of framework test fixtures.
- Moved the integration and benchmark backend from `examples/example-backend` to
  `tests/integration-backend`, so framework verification keeps testing the working tree.
- Faster releases: Gradle builds and publishes modules in parallel, Rust tests run once per
  release instead of twice, and the GitHub Release no longer waits for the GitHub Packages mirror.
- The feature-gated observability adapters are now tested with all features enabled.

## 0.6.0 — Complete trace waterfall and lower idle latency

- Completed the distributed trace waterfall. Java client spans now start before request encoding
  and end after response decoding, with `protobuf.encode/decode`, `moonlight.client.prepare`,
  `write`, `wait`, and `dispatch` child spans. Rust server spans start when the frame is read and
  end when the response is written, with `moonlight.server.queue`, `frame_decode`, `middleware`,
  and `write` stages. Every stage keeps its real start time; previously Rust stages were placed
  at the end of the request span.
- Generated Java clients call the new `MoonLightChannel.call(...)` with a `MoonLightCodec`.
  Regenerate clients to get the client-side stages.
- Server-streaming handlers now report their generated decode, handler, and per-item encode
  stages (bounded to 128 stages per request).
- Fixed `MoonLightBridge` (Java and Paper) dropping the generated `RpcPolicy`: the span name,
  compression preference, and policy limits now reach the transport.
- Added `RequestObservation::wants_timing` (Rust) and `RequestObservation.wantsStages()` (Java).
  OpenTelemetry adapters enable detailed timing only for sampled requests.
- `RequestInfo` gained `received_at` (Rust) and `startNanos` (Java). The previous Java
  constructors remain.
- Cut sequential call latency on an idle connection: the Java caller writes a lone request
  frame itself instead of waking the writer thread, responses are read by a platform thread
  instead of a virtual one, and the Rust server reads requests through a buffer. On the
  development host, p50 dropped from 70 to 51 µs on TCP loopback and from 62 to 38 µs on a Unix
  socket; pipelined throughput rose by roughly 20-40%.
- Added `MoonLightPerformanceOptions.ResponseCompletion`. `DIRECT` completes unary responses on
  the reader thread and saves one more thread hand-off (Unix p50 24 µs); non-async dependent stages
  must then not block. `ISOLATED` stays the default. The `lowestLatency()` preset now uses `DIRECT`.
- Made Paper/Folia shutdown wait for the bounded graceful drain before the plugin classloader is
  released, preventing late bridge work from reading a closed plugin JAR.
- Invalidated processed Paper example resources when the project version changes and verified the
  embedded `plugin.yml` version during `check`.
- Restored the local Paper test backend after scoped-route hardening by making its intentionally
  unsecured loopback policy explicit.

## 0.5.2 — Security, backpressure, and observability hardening

- Bounded queued Rust response bytes both globally and per connection, return
  `ResourceExhausted` instead of retaining oversized backlogs, and release peak writer-buffer
  allocations after large responses.
- Bounded Java server-stream callback delivery to 32 pending items and replenished wire credit only
  as callbacks finish, preventing a slow application executor from creating an unbounded backlog.
- Made generated authorization scopes fail closed unless the Rust router installs an explicit
  `authorization_layer(...)`; local tests must deliberately opt out with
  `allow_unsecured_scopes()`.
- Preserved Sentry span completion when its bounded finisher queue is saturated instead of
  silently discarding telemetry work.
- Expanded the provisioned Grafana dashboard with request rates, success/failure ratios, traffic,
  payload sizes, exporter health, connection churn, and Java/Rust latency views.
- Pinned GitHub Actions by immutable commit and narrowed workflow write permissions; updated Rust
  TLS/QUIC dependencies to audited patch releases.

## 0.5.1 — Developer telemetry maintenance

- Rotated the private Java and Rust framework-maintainer Sentry destinations after the previous
  projects were replaced.
- Added release-only Java source-context uploads so internal framework diagnostics resolve to the
  exact source while normal local and consumer builds remain offline.
- Updated the embedded Java Sentry SDK and async profiler to 8.60.0 while retaining runtime
  opt-out, destination overrides, constant diagnostics, and `sendDefaultPii=false`.

## 0.5.0 — Protocol v2 and code-first contracts

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

Wire protocol v2 has no v1 fallback. Deploy compatible 0.5 clients and servers together.

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
