# moonlight-bridge-server

[![crates.io](https://img.shields.io/crates/v/moonlight-bridge-server.svg)](https://crates.io/crates/moonlight-bridge-server)
[![docs.rs](https://docs.rs/moonlight-bridge-server/badge.svg)](https://docs.rs/moonlight-bridge-server)
[![license](https://img.shields.io/crates/l/moonlight-bridge-server.svg)](https://github.com/MoonL1ghtProject/MoonLightBridge#license)

The Tokio backend runtime for [MoonLightBridge](https://github.com/MoonL1ghtProject/MoonLightBridge),
a typed RPC bridge between Java Minecraft plugins and Rust services.

MoonLightBridge keeps Bukkit/Paper world access in Java and moves isolated business logic, state,
persistence and batchable computation to a Rust process. One long-lived connection carries many
concurrent requests; responses may complete out of order without blocking the Minecraft tick.

## Features

- TCP, Unix-domain sockets and mutual TLS;
- multiplexed concurrent request/response RPC;
- bounded per-connection work and response queues;
- deadlines, cancellation and a timeout for silent pre-HELLO clients;
- heartbeat, health/readiness and reconnect-safe server events;
- generated Protobuf service traits and router registration;
- structured remote errors and strict protocol validation;
- low-overhead metrics and pluggable request observations;
- idempotency and optimistic-revision helpers for safe mutations.

## Installation

```toml
[dependencies]
moonlight-bridge-server = "0.1.1"
tokio = { version = "1", features = ["macros", "rt-multi-thread"] }
```

Rust 1.88 or newer is required. Applications normally also use
[`moonlight-bridge-codegen`](https://crates.io/crates/moonlight-bridge-codegen) as a build dependency
to generate typed services from the same `.proto` contract as the Java plugin.

## Quick start

The raw router accepts byte payloads and is useful for smoke tests or a custom codec:

```rust,no_run
use moonlight_bridge_server::{HandlerError, Router, Server};

#[tokio::main]
async fn main() -> std::io::Result<()> {
    let router = Router::builder()
        .route(1, |body| async move {
            Ok::<Vec<u8>, HandlerError>(body)
        })
        .build();

    Server::bind_tcp("127.0.0.1:38191", router).await?.run().await
}
```

Production applications should use generated bindings instead of manually chosen method IDs:

```rust,ignore
use std::sync::Arc;
use moonlight_bridge_server::{HandlerError, Router, Server};
use profile_api::{
    ProfileService,
    model::{LoadProfileRequest, ProfileResponse},
    register_profile_service,
};

struct Profiles;

impl ProfileService for Profiles {
    async fn load_profile(
        &self,
        request: LoadProfileRequest,
    ) -> Result<ProfileResponse, HandlerError> {
        Ok(ProfileResponse {
            player_id: request.player_id,
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

The generator owns method IDs and fails on collisions. `RouterBuilder::route` also rejects duplicate
IDs during registration instead of silently replacing a handler.

## Choosing a transport

| Topology | Rust binding | Java endpoint | Notes |
|---|---|---|---|
| Same host/container | `bind_unix(path, router)` | `unix:/path/backend.sock` | Lowest local overhead; Unix only |
| Local development | `bind_tcp(addr, router).await` | `tcp://127.0.0.1:38191` | Plaintext loopback |
| Trusted private network | `bind_tcp(addr, router).await` | `tcp://host:38191` | Protect with network policy |
| Different host/trust domain | `bind_tls(addr, router, config).await` | `tls://host:38191` | Mutual TLS |

Pterodactyl containers do not share loopback: `127.0.0.1` inside the game container is not the Wings
host or another container. Use a shared mounted Unix socket, the private bridge address, or mTLS.
See the [deployment guide](https://github.com/MoonL1ghtProject/MoonLightBridge/blob/main/docs/deployment-pterodactyl.md).

### Mutual TLS

```rust,no_run
use moonlight_bridge_server::{Router, Server, tls::load_mtls_server_config};

# async fn run(router: Router) -> std::io::Result<()> {
let tls = load_mtls_server_config(
    "server-chain.pem",
    "server-key.pem",
    "client-ca.pem",
)?;
Server::bind_tls("0.0.0.0:38191", router, tls).await?.run().await
# }
```

The server validates client certificates. Java validates the server certificate and hostname; do
not disable hostname verification in production.

## Errors and deadlines

Handlers return `Result<Response, HandlerError>`. Use a precise wire code for expected failures:

```rust
use moonlight_bridge_server::{ErrorCode, HandlerError};

fn invalid_player() -> HandlerError {
    HandlerError::new(ErrorCode::InvalidRequest, "unknown player")
}
```

Available codes are `UnknownMethod`, `InvalidRequest`, `DeadlineExceeded`, `Cancelled`,
`ResourceExhausted` and `Internal`. Treat error messages as diagnostic text, not a stable machine
API. Put domain error variants in your Protobuf response when callers need structured business
failures.

Deadlines are transmitted as request metadata and enforced around the handler future. Cancellation
aborts the request task. External systems may still commit concurrently with timeout/disconnect, so
mutating calls need an idempotency key when the application intends to retry.

## Safe retryable mutations

`IdempotencyCache` coalesces concurrent operations with the same key and retains their result for a
bounded TTL:

```rust
use std::time::Duration;
use moonlight_bridge_server::idempotency::IdempotencyCache;

# async fn example() -> Result<(), String> {
let cache = IdempotencyCache::<String, u64, String>::new(10_000, Duration::from_secs(300));
let result = cache.execute("operation-uuid".to_owned(), || async {
    // Perform the mutation exactly once for this process/cache lifetime.
    Ok(42)
}).await?;
assert_eq!(result.value, 42);
# Ok(())
# }
```

`Revisioned<T>` provides in-memory optimistic concurrency with monotonically increasing revisions.
Both helpers are process-local; durable economy/inventory operations should enforce the same rules
inside the database transaction.

## Server events

Create an `EventHub` before moving the server into `run`, then pass it to generated publishers:

```rust,ignore
use moonlight_bridge_server::{EventHub, Router, Server};
use profile_api::{model::BalanceChangedEvent, publish_balance_changed_event};

let events = EventHub::new(1_024);
let server = Server::bind_tcp("127.0.0.1:38191", Router::builder().build())
    .await?
    .with_event_hub(events.clone());

let receivers = publish_balance_changed_event(
    &events,
    BalanceChangedEvent { player_id, balance },
);
```

Events are one-way and best-effort for currently connected clients. The broadcast capacity is
bounded. Do not use events as a durable queue; persist important state and let clients resynchronize
after reconnect.

## Health and metrics

Obtain handles before calling `run`:

```rust,no_run
use moonlight_bridge_server::{MoonLightMetrics, Router, Server, Telemetry};
use std::sync::Arc;

# async fn run() -> std::io::Result<()> {
let metrics = MoonLightMetrics::default();
let router = Router::builder()
    .telemetry(Arc::new(metrics.clone()) as Arc<dyn Telemetry>)
    .build();
let server = Server::bind_tcp("127.0.0.1:38191", router).await?;
let health = server.health();

tokio::spawn(async move { server.run().await });
let current = health.snapshot();
let totals = metrics.snapshot();
# Ok(())
# }
```

Health exposes readiness, uptime, active connections and active requests. Metrics expose started,
succeeded and failed requests, byte totals, total latency and maximum latency. Snapshots use atomic
counters and do not perform network I/O.

## Backpressure and performance

Each connection has a negotiated in-flight limit and bounded writer. The first response is written
without an intentional batching delay; responses already available in the same burst are coalesced
to reduce synchronization, syscalls and TLS records.

A blocking handler still blocks a Tokio worker. Use async database/network clients and
`spawn_blocking` for unavoidable blocking or CPU-heavy work. Prefer one coarse request or generated
batch per subsystem/tick over one RPC per Minecraft object.

See the project [performance guide](https://github.com/MoonL1ghtProject/MoonLightBridge/blob/main/docs/performance.md)
for measured latency, throughput, queue behavior and benchmark commands.

## Compatibility

The client begins with HELLO and the server replies WELCOME with negotiated limits/features. Invalid
frames, zero request IDs and duplicate active IDs are rejected. Interrupted requests are never
automatically replayed.

MoonLightBridge follows semantic versioning for its Rust API. The wire version is negotiated
separately. Pin a release for production and review the project
[changelog](https://github.com/MoonL1ghtProject/MoonLightBridge/blob/main/CHANGELOG.md) before updating.

Licensed under MIT OR Apache-2.0.
