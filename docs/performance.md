# Performance model

MoonLightBridge optimizes bursts without adding an intentional batching delay.

## Write path

Java callers never write to the socket directly after the handshake. They encode
a frame into a pooled buffer and offer it to a bounded queue. A dedicated
virtual-thread writer takes the first frame immediately, drains frames that are
already available, and emits the group with one write and flush.

The Rust response writer follows the same pattern with a reusable `Vec`: the
first response is not delayed, while an existing burst is coalesced up to 64
frames or 512 KiB.

This means batching benefits both local and remote transports. It primarily
reduces synchronization and syscall/TLS-record overhead; it does not wait to
make a batch larger.

## Presets

`MoonLightClient.connect(endpoint)` selects an automatic preset:

| Transport | Queue | Max frames/write | Max bytes/write |
|---|---:|---:|---:|
| Unix socket | 4096 | 64 | 256 KiB |
| TCP | 4096 | 64 | 512 KiB |
| TLS | 4096 | 32 | 64 KiB |

Override it when profiling justifies a different tradeoff:

```java
var latency = MoonLightClient.connect(
    endpoint,
    MoonLightPerformanceOptions.lowestLatency()
);

var throughput = MoonLightClient.connect(
    endpoint,
    MoonLightPerformanceOptions.maximumThroughput()
);
```

Setting `maxBatchFrames` to `1` disables write batching but retains the dedicated
writer and bounded queue. Buffer pooling can be disabled independently. The
bounded queue cannot be disabled because an unbounded producer is an OOM path.

## Batch RPC

Generated unary clients include a typed batch method:

```java
List<EchoResponse> responses = client.echoBatch(requests).join();
```

Each element remains an independent RPC with its own request ID, deadline,
error, and cancellation. The writer coalesces the burst physically, avoiding the
semantic problems of one all-or-nothing mega-request.

## Local baseline

Measured on the development machine with a release Rust backend, JDK 24, a
32-byte payload, 2,000 sequential samples and 25,600 pipelined requests:

| Transport | p50 | p95 | p99 | Pipelined throughput |
|---|---:|---:|---:|---:|
| TCP loopback | 75.3 µs | 100.5 µs | 125.7 µs | 96,674 req/s |
| Unix socket | 53.7 µs | 76.9 µs | 105.8 µs | 148,763 req/s |

The latest clean transport run measured TCP at 71.9/96.6/115.3 µs and 95,910
req/s. This benchmark uses `moonlight-bridge-client` without the Sentry provider, so it is a
transport baseline rather than a production-plugin simulation.

These are a regression baseline, not portable guarantees. Run
`./scripts/benchmark.sh` on the production CPU/kernel/JVM before tuning presets.

## Leaf plugin load test

`examples/paper-load-test-plugin` measures the shaded library as it is used by a
real plugin. On Leaf 1.21.11, JDK 24, loopback TCP, a release Rust backend, and
the default embedded telemetry policy, a one-million-request run at concurrency
128 produced 88,819-100,278 req/s across repeated runs, p50 1.24-1.41 ms and p99
2.01-2.31 ms, with zero failures. A 20,000-request sequential run measured p50
78.1 µs and p99 174.4 µs.

JFR identified protobuf encoding/decoding and `CompletableFuture` lifecycle as
the main Java allocation sources. Linux `perf` showed Tokio scheduling,
per-request semaphore handling, and the cancellation table on Rust. These are
currently required features. A Sentry scope lookup on every unsampled Java RPC
was not required and was moved behind the 0.1% sampling decision.

## Remaining allocation work

Response payloads are currently returned as owned `byte[]`/`Vec<u8>`. Fully
zero-copy public APIs would impose lifetimes on asynchronous user code and are
not yet justified. The current pool targets temporary frame and coalescing
buffers, where ownership is entirely internal and safe.
