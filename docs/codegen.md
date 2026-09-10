# Protobuf and service generation

MoonLightBridge uses standard Protocol Buffers schemas. `protoc` generates message classes
for Java, while `prost-build` generates Rust message types. `moonlight-bridge-codegen` reads
the same descriptor set and generates the RPC-specific layer:

- Java typed clients returning `CompletableFuture<Response>`;
- Rust service traits and router registration functions;
- typed Rust publishers and Java listeners for messages whose names end in `Event`;
- identical numeric method IDs in both languages.

Example schema:

```proto
service EchoService {
  rpc Echo(EchoRequest) returns (EchoResponse);
}
```

Generated Java usage:

```java
var channel = MoonLightClient.connect("unix:/run/moonlight-bridge/backend.sock");
var echo = new EchoServiceClient(channel);
var response = echo.echo(EchoRequest.newBuilder()
    .setMessage("hello")
    .build()).join();
```

Generated Rust contract:

```rust
impl EchoService for ExampleEchoService {
    async fn echo(&self, request: EchoRequest) -> Result<EchoResponse, HandlerError> {
        Ok(EchoResponse { message: request.message })
    }
}

let router = register_echo_service(Router::builder(), Arc::new(service)).build();
```

## Method IDs

The generator hashes the canonical name `protobuf.package.Service/Method` with
32-bit FNV-1a and writes the resulting constant into both outputs. Generation
fails if two methods in one descriptor set collide. Renaming a package, service,
or method therefore changes its wire ID and is a breaking API change.

## Schema compatibility lock

[`proto/schema.lock`](../proto/schema.lock) is a deterministic snapshot of
messages, fields, enums, services, and RPC signatures. Both Cargo generation and
the Gradle Java build check it automatically.

Compatible changes, such as adding a new field with a new number, pass without
updating the lock. Intentional schema changes are accepted with:

```bash
./gradlew :java:moonlight-bridge-example-api:updateSchemaLock
```

Review the lock diff before committing it. Removing a field is accepted only
when its old name and number are both reserved:

```proto
message Player {
  reserved 2;
  reserved "old_rank";
}
```

The checker rejects changed field types/numbers/names, removed messages and
services, changed RPC signatures, and enum number reuse.

## Developer build helpers

Install the generator CLI used by both build systems:

```bash
cargo install moonlight-bridge-codegen --version 0.1.1 --locked
```

Java projects can apply the published Gradle plugin:

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
    java
    id("ru.moonlightproject.bridge") version "0.1.1"
}

moonlightBridge {
    // These are the defaults; override only when the project layout differs.
    protoDirectory.set(layout.projectDirectory.dir("src/main/proto"))
    schemaLock.set(layout.projectDirectory.file("schema.lock"))
    codegenExecutable.set("moonlight-bridge-codegen")
}
```

Its `moonlightBridge` extension configures proto/schema paths and executable
names; generation is wired into `compileJava` and compatibility validation into
`check`.

Rust API crates use `moonlight_bridge_codegen::compile_rust_api(RustBuildConfig
{ ... })` from `build.rs` to run prost, check the schema lock, and generate the
service layer in one call.

```toml
[build-dependencies]
moonlight-bridge-codegen = "0.1.1"
prost-build = "0.14"
```

## Current M2 limits

- unary request/response methods only;
- top-level Protobuf message types;
- events use the explicit `*Event` message naming convention;
- Java generation currently invokes the locally installed `protoc`.

Streaming methods are rejected during generation instead of silently producing
incorrect bindings.
