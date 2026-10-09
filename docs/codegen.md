# Contract and service generation

MoonLightBridge always compiles a standard Protobuf descriptor and generates the same Java clients,
Rust service traits, batch helpers, server streams, and typed events. The source of that descriptor
is a project choice:

- annotated Java source;
- annotated Kotlin source through KSP;
- annotated Rust source;
- handwritten `.proto` files;
- or code-first and handwritten schemas together.

There is no required project manifest or additional schema language. Code-first users write
ordinary language source, while teams that already own Protobuf schemas keep using them unchanged.

## Java code-first

Add `moonlight-bridge-code-first` to `annotationProcessor` and point the Gradle plugin at a small
contract source directory. The default is `src/main/moonlightContract` so contract stubs do not
become application runtime classes.

```java
package example.profile;

import java.util.List;
import ru.moonlightproject.bridge.codegen.*;

@MoonLightContract(
    protoPackage = "example.profile.v1",
    javaPackage = "example.profile.generated")
interface ProfileContract {
    @MoonLightMessage
    record LoadProfileRequest(String playerId, List<String> fields) {}

    @MoonLightMessage
    record ProfileResponse(String playerId, long balance) {}

    @MoonLightEnumeration
    enum DeliveryState { UNKNOWN, READY }

    @MoonLightService
    interface ProfileService {
        @MoonLightRpc(
            timeoutMs = 2_000,
            maxAttempts = 3,
            idempotency = "read_only",
            requiredScopes = {"profile.read"},
            compression = "prefer")
        ProfileResponse loadProfile(LoadProfileRequest request);
    }
}
```

Records map their components to message fields. Classes and Kotlin data classes use declared
properties. `List<T>` becomes `repeated T`, boxed JVM scalars become `optional`, and a top-level
message ending in `Event` receives typed publish/subscribe bindings.

The annotations are real compile-time symbols, so IntelliJ IDEA and other Java IDEs provide normal
completion, navigation, refactoring, type checking, formatting, and syntax highlighting. Generated
Protobuf and Java sources remain visible under `build/` for inspection, but are not edited.

## Kotlin code-first

Apply KSP and register the same processor on the `ksp` configuration:

```kotlin
plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("ru.moonlightproject:moonlight-bridge-code-first:0.5.0")
    ksp("ru.moonlightproject:moonlight-bridge-code-first:0.5.0")
}

ksp {
    arg("moonlight.output", layout.buildDirectory.file("generated/schema/contract.proto")
        .get().asFile.absolutePath)
    arg("moonlight.schemaLock", layout.projectDirectory.file("schema.lock")
        .asFile.absolutePath)
}
```

Kotlin contracts use the same annotations. A service method may be `suspend`; nullable scalar
properties become `optional`, and lists become repeated fields. The complete build and a matching
Rust contract are in [`examples/kotlin-code-first-contract`](../examples/kotlin-code-first-contract).

## Rust code-first

The `moonlight-bridge-contract` attributes are no-op procedural macros at compile time. This keeps
the contract valid Rust for rust-analyzer while `moonlight-bridge-codegen` reads it during the build.

```rust
#[moonlight::contract(
    package = "example.profile.v1",
    java_package = "example.profile.generated"
)]
mod contract {
    #[moonlight::message]
    pub struct LoadProfileRequest {
        pub player_id: String,
        pub fields: Vec<String>,
    }

    #[moonlight::message]
    pub struct ProfileResponse {
        pub player_id: String,
        pub balance: i64,
    }

    #[moonlight::service]
    pub trait ProfileService {
        #[moonlight::rpc(
            timeout_ms = 2000,
            max_attempts = 3,
            idempotency = "read_only",
            required_scope = "profile.read",
            compression = "prefer"
        )]
        async fn load_profile(request: LoadProfileRequest) -> ProfileResponse;
    }
}
```

Use the normal build-script pipeline:

```rust,no_run
fn main() -> Result<(), Box<dyn std::error::Error>> {
    moonlight_bridge_codegen::compile_rust_source_api(
        moonlight_bridge_codegen::RustSourceBuildConfig {
            source: "src/contract.rs".into(),
            additional_protos: vec![],
            includes: vec!["../proto".into()],
            schema_lock: "schema.lock".into(),
            generated_services_name: "moonlight_bridge_services.rs".into(),
        },
    )?;
    Ok(())
}
```

`String`, integers, floats, booleans, `Option<T>`, `Vec<T>`, `Vec<u8>`, message names, and fieldless
enums are supported. `server_streaming` on `#[moonlight::rpc]` generates a server stream. Standard
Rust syntax highlighting, navigation, formatting, and compiler diagnostics remain available.

## Handwritten Protobuf and mixed contracts

Handwritten schemas remain the best option for existing contracts or advanced Protobuf constructs:

```proto
syntax = "proto3";
package example.profile.v1;
option java_package = "example.profile.generated";
option java_multiple_files = true;

service ProfileService {
  rpc LoadProfile(LoadProfileRequest) returns (ProfileResponse);
}

message LoadProfileRequest { string player_id = 1; }
message ProfileResponse { string player_id = 1; int64 balance = 2; }
```

The Gradle plugin treats both `src/main/proto` and `src/main/moonlightContract` as optional. A
proto-only project does not need the annotation processor, and a code-first project may have no
handwritten application `.proto` files. When both exist, they are compiled into one descriptor.

```kotlin
plugins {
    java
    id("ru.moonlightproject.bridge") version "0.5.0"
}

dependencies {
    annotationProcessor("ru.moonlightproject:moonlight-bridge-code-first:0.5.0")
    compileOnly("ru.moonlightproject:moonlight-bridge-code-first:0.5.0")
}

moonlightBridge {
    protoDirectory.set(layout.projectDirectory.dir("src/main/proto"))
    contractSourceDirectory.set(layout.projectDirectory.dir("src/main/moonlightContract"))
    schemaLock.set(layout.projectDirectory.file("schema.lock"))
}
```

The `0.5.0` coordinates above describe the source version on `main`; use them after the 0.5.0
release is published. The latest currently published release remains 0.4.0.

## Stable numbers and compatibility

Code-first declarations deliberately omit field numbers. On the first build the generator assigns
deterministic numbers. Later builds reuse the values recorded in `schema.lock`; removed members are
emitted as reserved names and numbers so they cannot be accidentally reused. The lock also records
services, RPC signatures, enum values, and generated policies.

For handwritten schemas, choose field numbers normally and reserve removed names and values:

```proto
message ProfileResponse {
  reserved 2;
  reserved "old_balance";
  string player_id = 1;
}
```

Generation rejects changed field identities or types, removed services, changed RPC signatures,
unsafe enum changes, removed reservations, incompatible policies, and method-ID collisions. Review
the lock diff before intentionally accepting a contract change.

## Method IDs and policies

RPC IDs are 32-bit FNV-1a hashes of `protobuf.package.Service/Method`. Java, Kotlin, Rust, and
handwritten Protobuf frontends produce identical descriptors and IDs for equivalent contracts.
Renaming a package, service, or method is therefore a breaking wire change.

`@MoonLightRpc`, `#[moonlight::rpc]`, and the Protobuf
`(moonlight.bridge.options.v1.rpc_policy)` option all compile to the same policy: deadline, stream
idle timeout, retry/backoff, idempotency, request/response limits, scopes, compression, and trace
sampling. The hot request path consumes generated constants and does not reflect over descriptors.

## Current limits

- unary and server-streaming response methods are supported;
- request, response, enum, and event types are top-level within one contract;
- events use the explicit `*Event` suffix;
- Java output still invokes a local `protoc`;
- client and bidirectional streaming are rejected instead of partially generated;
- use handwritten `.proto` for maps, oneofs, nested Protobuf messages, custom options beyond the
  MoonLightBridge policy, or imported third-party message models.
