# moonlight-bridge-server

The Tokio backend runtime for [MoonLightBridge](https://github.com/MoonL1ghtProject/MoonLightBridge),
a typed RPC bridge between Minecraft plugins and Rust services.

```rust
use moonlight_bridge_server::{HandlerError, Router, Server};

#[tokio::main]
async fn main() -> std::io::Result<()> {
    let router = Router::builder()
        .route(1, |body| async move { Ok::<_, HandlerError>(body) })
        .build();

    Server::bind_tcp("127.0.0.1:38191", router).await?.run().await
}
```

The runtime supports TCP, Unix-domain sockets and mutual TLS, concurrent multiplexed
requests, bounded writers, deadlines, cancellation, heartbeat, typed events, health
checks, idempotency helpers, metrics and pluggable telemetry.

Use `moonlight-bridge-codegen` for schema-first typed service traits and router bindings.
Licensed under MIT OR Apache-2.0.
