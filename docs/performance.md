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

## Compression

Protocol v2 negotiates `none` and Zstandard per connection. The default policy considers payloads
of at least 1 KiB, requires at least 64 bytes of savings, and uses Zstandard level 1. Small payloads
and peers that negotiate only `none` bypass the codec. An attempted compression that does not save
enough bytes retains the original allocation.

The protocol crate includes an ignored release-mode matrix benchmark:

```shell
cargo test -p moonlight-bridge-protocol --test compression_v2 --release -- --ignored --nocapture
```

On the development machine, 2,000 encode/decode iterations gave the following local costs. The
64 KiB compressible fixture contains repeated 4 KiB pseudorandom blocks; the incompressible fixture
uses distinct pseudorandom bytes. These figures guide thresholds and are not portable guarantees.

| Case | Input | Wire payload | Selected codec | Encode/decode round trip |
|---|---:|---:|---|---:|
| Codec disabled | 64 KiB | 64 KiB | none | 53.7 µs |
| Below threshold | 1,023 B | 1,023 B | none | 0.16 µs |
| Compressible | 64 KiB | 4,119 B | zstd | 71.9 µs |
| Incompressible | 64 KiB | 64 KiB | none | 59.5 µs |

The client and server enforce decoded size, expansion ratio, and a shared in-flight decoded-byte
budget before allocating codec output. This keeps hostile compressed inputs from turning bandwidth
savings into unbounded memory pressure.

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
32-byte payload, 1,000 warm-up calls, 2,000 sequential samples and 25,600
pipelined requests per run. Each value is the median of three complete runs:

| Version | Transport | p50 | p95 | p99 | Pipelined throughput |
|---|---|---:|---:|---:|---:|
| v0.4.0, protocol v1 | TCP loopback | 92.8 µs | 129.7 µs | 178.8 µs | 137,424 req/s |
| v0.5 before metadata fast path | TCP loopback | 108.6 µs | 151.9 µs | 187.1 µs | 112,711 req/s |
| v0.5 optimized | TCP loopback | 102.8 µs | 148.0 µs | 171.0 µs | 124,463 req/s |
| v0.4.0, protocol v1 | Unix socket | 75.4 µs | 108.8 µs | 128.1 µs | 143,360 req/s |
| v0.5 before metadata fast path | Unix socket | 84.9 µs | 115.3 µs | 153.6 µs | 119,447 req/s |
| v0.5 optimized | Unix socket | 76.4 µs | 100.5 µs | 121.3 µs | 132,301 req/s |

The v0.5 fast path reuses empty metadata, skips interceptor context creation when
there are no interceptors, writes mandatory protocol-v2 metadata directly into
the pooled frame, and reuses the virtual-thread factory. Compared with the
pre-optimization v0.5 median, pipelined throughput improved by about 10% on TCP
and 11% on Unix sockets. The remaining throughput difference from v0.4 includes
the mandatory bounded metadata encoding and decoding added by protocol v2.

This benchmark uses `moonlight-bridge-client` without the bundled runtime
instrumentation, so it is a transport baseline rather than a production-plugin simulation.

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
currently required features. Remote tracing work on every unsampled Java RPC was
not required and was moved behind the sampling decision.

## Remaining allocation work

Response payloads are currently returned as owned `byte[]`/`Vec<u8>`. Fully
zero-copy public APIs would impose lifetimes on asynchronous user code and are
not yet justified. The current pool targets temporary frame and coalescing
buffers, where ownership is entirely internal and safe.
