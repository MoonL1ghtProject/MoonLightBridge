# Development and verification

## Requirements

- stable Rust toolchain with `cargo`, `rustfmt`, and Clippy;
- JDK 21 or newer;
- Protocol Buffers compiler (`protoc`);
- OpenSSL for the complete TCP, Unix socket, and mTLS integration suite.

Use the checked-in Gradle wrapper instead of relying on a system Gradle version.

## Repository layout

| Path | Purpose |
|---|---|
| `crates/` | Rust protocol, server, telemetry, schema generator, and generated example API |
| `java/` | Java transport, Paper/Folia facade, telemetry provider, and Gradle plugin |
| `proto/` | Shared protobuf contract and compatibility lock |
| `examples/` | Example Rust backends and shaded Paper plugins |
| `scripts/` | End-to-end integration, example, and performance runners |
| `docs/` | Protocol, deployment, performance, security, and SDK documentation |

## Fast checks

Run the same strict Rust checks used by GitHub Actions:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

Compile and test all Java modules and example plugins:

```bash
./gradlew --no-daemon test
```

## End-to-end checks

The complete integration runner verifies generated APIs, TCP, Unix sockets,
mTLS, protocol validation, and reconnect behavior:

```bash
./scripts/integration-test.sh
```

Build and smoke-test the embedded Paper example:

```bash
./scripts/test-paper-example.sh
```

Measure the local TCP and Unix socket baselines with a release Rust backend:

```bash
./scripts/benchmark.sh
```

Test runners keep transient backend logs outside the repository. If a runner
fails, it prints the tail of the backend log before removing its temporary
directory.

## Generated files and repository hygiene

Cargo and Gradle outputs, IDE state, Qodana local results, JVM crash dumps,
profiles, and test logs are ignored. Generated Java/Rust bindings are produced
from `proto/` during the build and are not committed. The Gradle wrapper JAR and
`Cargo.lock` are intentionally tracked for reproducible builds.

Do not commit credentials, Sentry authentication tokens, Qodana tokens, TLS
private keys, generated reports, or server runtime data. Framework-owned Sentry
DSNs may be embedded by telemetry policy; unlike auth tokens, DSNs are project
routing identifiers rather than account credentials.

## Continuous integration

The Qodana workflow has two independent jobs:

- native Qodana Community for JVM with its report uploaded to Qodana Cloud and
  retained as a GitHub artifact;
- Rust formatting, Clippy with warnings denied, and the complete Rust test suite.

`QODANA_TOKEN` is configured as a GitHub Actions secret and must never be placed
in workflow YAML or source files.
