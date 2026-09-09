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
let tls = expj_server::tls::load_mtls_server_config(
    "server-chain.pem",
    "server-key.pem",
    "client-ca.pem",
)?;

Server::bind_tls("0.0.0.0:38191", router, tls)
    .await?
    .run()
    .await
```

The private key must be readable only by the backend account. Use separate
client certificates per Minecraft server or trust domain so certificates can be
revoked and rotated independently.

## Java client

`ExpjClient.connect("tls://host:38191")` uses the default JVM `SSLContext`.
Deployments can configure `javax.net.ssl.keyStore` and
`javax.net.ssl.trustStore`, or construct an `SSLContext` programmatically and
call:

```java
ExpjClient.tls("host", 38191, sslContext);
```

Do not disable trust or hostname validation in production.

## Reconnect semantics

`ReconnectingExpjClient` uses capped exponential backoff with jitter. Pending
requests fail when the physical connection dies, calls made while disconnected
fail immediately, and only newly submitted calls use the recovered connection.
This prevents accidental replay of non-idempotent economy and inventory writes.
