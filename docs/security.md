# Transport security

## Modes

- `unix:` relies on filesystem ownership and directory permissions.
- `tcp:` is plaintext and intended only for trusted private networks.
- `tls:` uses TLS with mandatory client authentication on the Rust server.

The Java client enables hostname verification. A certificate for a raw IP must
contain that IP in its Subject Alternative Name; setting only a Common Name is
not sufficient.

## Rust server

```rust
let tls = moonlight_bridge_server::tls::load_mtls_server_config(
    "server-chain.pem",
    "server-key.pem",
    "client-ca.pem",
)?;

Server::bind_tls("0.0.0.0:38191", router, tls)
    .await?
    .with_limits(moonlight_bridge_server::ServerLimits {
        max_connections: 512,
        max_buffered_request_bytes: 32 * 1024 * 1024,
        ..Default::default()
    })
    .run()
    .await
```

The defaults cap accepted connections, decoded request bytes held across connections,
the time to receive each frame, and response-writer shutdown. Tune these process-wide
limits to the backend memory budget; per-connection negotiated request concurrency remains
an additional bound.

The private key must be readable only by the backend account. Use separate
client certificates per Minecraft server or trust domain so certificates can be
revoked and rotated independently.

## Java client

`MoonLightClient.connect("tls://host:38191")` uses the default JVM `SSLContext`.
Deployments can configure `javax.net.ssl.keyStore` and
`javax.net.ssl.trustStore`, or construct an `SSLContext` programmatically and
call:

```java
MoonLightClient.tls("host", 38191, sslContext);
```

Do not disable trust or hostname validation in production.

## Reconnect semantics

`ReconnectingMoonLightClient` uses capped exponential backoff with jitter. Pending
requests fail when the physical connection dies, calls made while disconnected
fail immediately, and only newly submitted calls use the recovered connection.
This prevents accidental replay of non-idempotent economy and inventory writes.

## Telemetry privacy and runtime policy

The embedded Java provider reports constant framework diagnostics and stable error codes.
It does not attach application exception messages, causes or stack traces to error events
or request/function spans. Do not put secrets in method names or telemetry stage names.

Set policy before the first MoonLightBridge use (the provider is initialized once):

```text
-Dmoonlight.bridge.telemetry.enabled=false
```

The equivalent environment variable is `MOONLIGHT_BRIDGE_TELEMETRY_ENABLED=false`.
To use your own destination, set `moonlight.bridge.telemetry.dsn` or
`MOONLIGHT_BRIDGE_TELEMETRY_DSN`; an empty destination disables the provider.
System properties take precedence over environment variables. Disabling the provider
prevents Sentry initialization, including profiling. The local metrics SPI remains usable.
Telemetry completion has a bounded queue and drops optional telemetry on saturation;
it never executes overflow work on the transport caller.

The example backend refuses incomplete or empty TLS environment settings. Configure
all of certificate, key and client CA together; a partial TLS configuration is an error,
not an instruction to fall back to plaintext.
