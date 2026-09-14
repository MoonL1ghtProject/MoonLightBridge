<div align="center">
  <img src="docs/assets/moonlightbridge-banner.svg" alt="MoonLightBridge" width="820">

  <p><strong>A fast, typed bridge between Java applications and Rust backends.</strong></p>
  <p>Use the universal Java runtime anywhere, or add the scheduler-safe Paper/Folia layer for Minecraft.</p>

  [![Release](https://img.shields.io/github/v/release/MoonL1ghtProject/MoonLightBridge?style=flat-square&color=7c5cff)](https://github.com/MoonL1ghtProject/MoonLightBridge/releases)
  [![Maven Central](https://img.shields.io/maven-central/v/ru.moonlightproject/moonlight-bridge-java?style=flat-square&color=blue)](https://central.sonatype.com/namespace/ru.moonlightproject)
  [![Stars](https://img.shields.io/github/stars/MoonL1ghtProject/MoonLightBridge?style=flat-square&color=f4c542)](https://github.com/MoonL1ghtProject/MoonLightBridge/stargazers)
  [![Java 21+](https://img.shields.io/badge/Java-21%2B-f89820?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
  [![Rust 1.88+](https://img.shields.io/badge/Rust-1.88%2B-000000?style=flat-square&logo=rust)](https://www.rust-lang.org/)
  [![Qodana](https://github.com/MoonL1ghtProject/MoonLightBridge/actions/workflows/qodana.yml/badge.svg)](https://github.com/MoonL1ghtProject/MoonLightBridge/actions/workflows/qodana.yml)
  [![License](https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-2ea44f?style=flat-square)](#license)
</div>

MoonLightBridge is an embedded Java 21+ library, not a sidecar SDK or mandatory Minecraft plugin.
Describe an API once with Protocol Buffers and call a Rust service through a generated
`CompletableFuture` client. The same API works in ordinary JVM applications and Paper/Folia plugins,
over TCP, mutual TLS, or Unix-domain sockets.

## Why MoonLightBridge?

- **Typed end to end.** One `.proto` schema generates Java clients, Rust service traits,
  batch methods and server events.
- **Built for the hot path.** Multiplexed requests, a dedicated bounded writer, burst
  coalescing and reusable buffers keep the bridge overhead small.
- **Universal first.** The lifecycle, reconnect, batching, health and event API has no Bukkit
  dependency; Paper/Folia support is a thin scheduler-aware adapter.
- **Failure is explicit.** Deadlines, cancellation, heartbeat, reconnect supervision,
  backpressure, protocol validation and structured remote errors are part of the core.
- **Operational by default.** Health, metrics, errors, sampled traces and JFR hooks require no
  application boilerplate and never include request or response bodies.

```mermaid
flowchart LR
    A[Java 21+ application] --> J[moonlight-bridge-java]
    P[Paper / Folia plugin] --> PA[moonlight-bridge-paper]
    PA --> J
    J -->|generated async client · UDS · TCP · mTLS| R[MoonLightBridge Rust server]
    R --> S[Your services and storage]
    R -. typed events .-> J
```

## Install

MoonLightBridge `0.2.2` is available from Maven Central without repository credentials.

For any Java 21+ application:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("ru.moonlightproject:moonlight-bridge-java:0.2.2")
}
```

For Paper or Folia, use the adapter instead; it already includes the universal runtime:

```kotlin
dependencies {
    implementation("ru.moonlightproject:moonlight-bridge-paper:0.2.2")
}
```

| Artifact | Use it for |
|---|---|
| `moonlight-bridge-java` | Recommended lifecycle/runtime API for any Java 21+ application |
| `moonlight-bridge-paper` | Paper/Folia lifecycle and scheduler-safe completion callbacks |
| `moonlight-bridge-client` | Low-level transport SPI for generated code and advanced integrations |
| `moonlight-bridge-framework` | Compatibility aggregate for applications written against 0.1.x |
| `ru.moonlightproject.bridge` | Gradle plugin that generates typed Java clients from Protobuf |

MoonLightBridge is embedded into your plugin. If you build a shaded JAR, relocate its
dependencies and merge service descriptors:

```kotlin
tasks.shadowJar {
    mergeServiceFiles()
    relocate("com.google.protobuf", "your.plugin.internal.protobuf")
}
```

Artifacts are also mirrored to GitHub Packages by the release pipeline. Maven Central is the
recommended source because it needs no GitHub credentials; see
[the publishing guide](docs/publishing.md#github-packages) when you specifically want the
GitHub registry.

Add the Rust runtime to the backend:

```toml
[dependencies]
moonlight-bridge-server = "0.2.2"
tokio = { version = "1", features = ["macros", "rt-multi-thread"] }
```

Requirements are Java 21 or newer, Rust 1.88 or newer, and `protoc` for schema generation.

## Define an API once

```proto
syntax = "proto3";
package my.plugin.v1;

service ProfileService {
  rpc LoadProfile(LoadProfileRequest) returns (Profile);
}

message LoadProfileRequest { string player_id = 1; }
message Profile { string display_name = 1; int64 balance = 2; }
```

Apply the generator to the Java API module:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("ru.moonlightproject.bridge") version "0.2.2"
}

dependencies {
    implementation("ru.moonlightproject:moonlight-bridge-client:0.2.2")
    implementation("com.google.protobuf:protobuf-java:4.36.1")
}
```

The plugin reads `src/main/proto`, generates Protobuf messages and typed clients, then
checks `schema.lock` during `check`. The Rust side uses the same descriptor through
`moonlight-bridge-codegen`; details and the complete `build.rs` are in
[the code generation guide](docs/codegen.md).

## Call Rust from Java

Generated clients accept the common `MoonLightChannel` interface. `start` returns immediately and
reconnects in the background; `connect` waits for the first connection and is convenient for a CLI
or service startup that should fail fast:

```java
import java.time.Duration;
import ru.moonlightproject.bridge.MoonLightBridge;

try (var bridge = MoonLightBridge.connect("tcp://127.0.0.1:38191")) {
    var profiles = new ProfileServiceClient(bridge.channel());
    var request = LoadProfileRequest.newBuilder()
        .setPlayerId("b98e6a77-cc91-4e94-8e1e-4d40c86a70cd")
        .build();

    var profile = profiles.withDeadline(Duration.ofMillis(250))
        .loadProfile(request)
        .join();
    System.out.println(profile.getDisplayName());
}
```

For long-running applications, keep one bridge per backend endpoint for the process lifetime. Do
not open a connection for every call. `firstConnection()`, `isConnected()`, `lastFailure()`,
`health()`, `pendingRequests()`, `warmUp(...)` and typed event subscriptions cover lifecycle and
operations without requiring direct access to the transport implementation.

## Call Rust from Paper or Folia

```java
public final class ProfilesPlugin extends JavaPlugin {
    private MoonLightBridge bridge;
    private ProfileServiceClient profiles;

    @Override
    public void onEnable() {
        try {
            bridge = MoonLightBridge.start(this, "unix:/run/moonlightbridge/backend.sock");
            profiles = new ProfileServiceClient(bridge.channel());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot start MoonLightBridge", error);
        }
    }

    public void showProfile(Player player) {
        var request = LoadProfileRequest.newBuilder()
            .setPlayerId(player.getUniqueId().toString())
            .build();

        bridge.call(profiles.loadProfile(request))
            .whenCompleteFor(player, (profile, error) -> {
                if (error != null) {
                    player.sendMessage("Backend unavailable: " + error.getMessage());
                    return;
                }
                player.sendMessage(profile.getDisplayName() + ": " + profile.getBalance());
            });
    }

    @Override
    public void onDisable() {
        if (bridge == null) return;
        try {
            bridge.close();
        } catch (IOException error) {
            getLogger().warning("MoonLightBridge shutdown failed: " + error.getMessage());
        }
    }
}
```

`whenCompleteFor` dispatches safely for the target entity. Global and region-aware
variants are available for work that is not tied to a player.

## Implement the backend

The generator creates the `ProfileService` trait and `register_profile_service` function:

```rust
use std::sync::Arc;
use moonlight_bridge_server::{HandlerError, Router, Server};
use my_plugin_api::{
    ProfileService, register_profile_service,
    model::{LoadProfileRequest, Profile},
};

struct Profiles;

impl ProfileService for Profiles {
    async fn load_profile(
        &self,
        request: LoadProfileRequest,
    ) -> Result<Profile, HandlerError> {
        Ok(Profile {
            display_name: request.player_id,
            balance: 1_000,
        })
    }
}

#[tokio::main]
async fn main() -> std::io::Result<()> {
    let router = register_profile_service(Router::builder(), Arc::new(Profiles)).build();
    Server::bind_tcp("127.0.0.1:38191", router).await?.run().await
}
```

For Pterodactyl, a shared Unix socket is the fastest same-host topology. Use mTLS when
the backend is in another container or on another machine. The supported layouts are
documented in [deployment-pterodactyl.md](docs/deployment-pterodactyl.md).

## What is included

| Area | Available in 0.2.2 |
|---|---|
| Transport | Unix socket, TCP, mutual TLS |
| RPC | Multiplexing, typed unary calls, typed batches, deadlines, cancellation |
| Load control | Bounded outgoing/in-flight queues, dedicated writer, write coalescing |
| Lifecycle | HELLO timeout, heartbeat, reconnect, health/readiness |
| Server push | Reconnect-safe typed Rust-to-Java events |
| Safety | Payload limits, method/response validation, duplicate-ID rejection |
| Java | Universal Java 21+ lifecycle API with no Bukkit dependency |
| Minecraft | Thin Paper/Folia scheduler-aware adapter, no separate bridge plugin |
| Operations | Health, local metrics, structured errors and JFR events |

## Documentation

- [Architecture and scope](docs/architecture.md)
- [Universal Java SDK](docs/java-sdk.md)
- [Minecraft SDK](docs/minecraft-sdk.md)
- [Protocol reference](docs/protocol.md)
- [Schema and code generation](docs/codegen.md)
- [Performance and tuning](docs/performance.md)
- [Pterodactyl deployment](docs/deployment-pterodactyl.md)
- [Security model](docs/security.md)
- [Development and verification](docs/development.md)
- [Publishing and releases](docs/publishing.md)
- [Changelog](CHANGELOG.md)

The repository contains a working [Paper plugin](examples/paper-test-plugin), its
[Rust backend](examples/test-plugin-backend), and a separate
[load-test plugin](examples/paper-load-test-plugin).

## Project status

Version `0.2.2` is a stable public API release. The transport and
lifecycle are fully tested, but the project is still young: benchmark your own workload
and pin exact versions in production. Backward-incompatible changes follow semantic
versioning.

## Contributing

Bug reports, focused pull requests and reproducible performance profiles are welcome.
Read [CONTRIBUTING.md](CONTRIBUTING.md) before sending a change. The full local check is:

```bash
./scripts/integration-test.sh
```

## License

Copyright © 2026 ~VicTim~ and MoonLightProject contributors.

MoonLightBridge is available under your choice of the
[MIT License](LICENSE-MIT) or [Apache License 2.0](LICENSE-APACHE).
