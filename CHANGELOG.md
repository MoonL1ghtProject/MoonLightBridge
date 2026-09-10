# Changelog

MoonLightBridge follows [Semantic Versioning](https://semver.org/). This file records
user-visible framework changes; low-level refactors stay in the Git history.

## 0.1.1 — Rust documentation

- Expanded crates.io and docs.rs documentation for the server, code generator, and wire protocol.
- Added checked examples for transports, routing, schema locks, errors, metrics, events, and safe mutations.
- Made the framework-owned Rust Sentry adapter private and ended its separate crates.io publication.
- Separated optional Maven Central publishing from crates.io, GitHub Packages, and GitHub Releases.

## 0.1.0 — first public release

- Added multiplexed Java-to-Rust RPC over TCP, Unix sockets and mutual TLS.
- Added generated Protobuf Java clients, Rust services, typed batches and server events.
- Added bounded dedicated writers, burst coalescing, buffer reuse and explicit backpressure.
- Added deadlines, cancellation, heartbeat, reconnect supervision and health/readiness.
- Added Paper/Folia scheduler-aware completions without a separate bridge server plugin.
- Added schema compatibility locking, duplicate method/request protection and strict response validation.
- Added framework-owned Sentry errors/traces, propagated spans, metrics and Java Flight Recorder events.
- Added Maven Central, GitHub Packages, crates.io and checksummed GitHub Release automation.
