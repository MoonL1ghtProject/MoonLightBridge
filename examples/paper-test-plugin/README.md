# MoonLightBridge Paper test plugin

This example is a normal Paper plugin with MoonLightBridge embedded into its
shaded JAR. The server does not need a separate MoonLightBridge plugin. Its Rust
backend implements the generated `EchoService` and demonstrates one request as
well as a batch call.

## Java and Paper compatibility

All MoonLightBridge Java modules and this plugin are emitted as Java 21 class files
(`--release 21`). They run on Java 21 and later JVMs, including Java 25 and 26.
The example compiles against Paper 1.21.4 because that is the Java 21 baseline.
A particular Paper server can impose a newer JVM requirement independently of
the plugin; for example, modern Paper 26.x requires Java 25.

The plugin only uses stable Bukkit/Paper API shared by the supported releases.
Calls to Rust run asynchronously and `MoonLightBridge` schedules Bukkit access
onto the correct Paper global or Folia entity/region scheduler. During
asynchronous connection the example performs one hidden
warm-up RPC so generated Protobuf classes, Sentry profiling, and the complete
transport path are initialized before a command can use the client.

## Build and test

Run the complete Java-to-Rust smoke test:

```bash
./scripts/test-paper-example.sh
```

It verifies a generated unary RPC and a 32-request batch, then creates:

```text
examples/paper-test-plugin/build/libs/paper-test-plugin-0.1.0.jar
```

To run it on Paper:

```bash
cargo run --release --package moonlight-bridge-test-plugin-backend
./gradlew :examples:paper-test-plugin:shadowJar
```

Copy the resulting JAR into `plugins/`, start Paper, and use:

```text
/moonlighttest Hello Rust
/moonlightbatch 64 Hello batch
```

Command output reports network/backend `RPC` time separately from `callback`
time. The callback value includes waiting for the next Paper/Folia scheduler
boundary and can approach one 20 TPS tick (50 ms) even when RPC itself takes
less than a millisecond.

The default endpoint is `tcp://127.0.0.1:38201`. Change `endpoint` in the
plugin's `config.yml` for a different host/container. A shared-volume Unix
socket can instead use `unix:/path/to/backend.sock` and start the backend with
`MOONLIGHT_BRIDGE_UNIX_PATH=/path/to/backend.sock`.

The shaded JAR includes the framework, generated API, Protobuf runtime, and the
framework-owned Sentry provider. Protobuf and Sentry are relocated under
`ru.moonlightproject.bridge.internal` so versions exposed by Paper forks cannot
conflict with MoonLightBridge. Plugin code neither imports nor initializes
Sentry.
