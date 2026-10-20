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
32-byte payload, 5,000 sequential warm-up calls and 5,000 latency samples.
The pipelined path uses batches of 128, 40 warm-up batches and five measured
windows of 25,600 requests. Each table value is the median of three complete
JVM runs; throughput within each run is itself the median of five windows:

| Version | Transport | p50 | p95 | p99 | Pipelined throughput |
|---|---|---:|---:|---:|---:|
| v0.4.0, protocol v1 | TCP loopback | 83.5 µs | 114.6 µs | 135.1 µs | 145,330 req/s |
| v0.5 Java fast path only | TCP loopback | 81.0 µs | 135.0 µs | 210.5 µs | 127,485 req/s |
| v0.5 Java + borrowed Rust metadata | TCP loopback | 70.1 µs | 90.5 µs | 108.5 µs | 137,500 req/s |
| v0.5.2, protocol v2 | TCP loopback | 73.2 µs | 114.7 µs | 134.1 µs | 132,451 req/s |
| v0.4.0, protocol v1 | Unix socket | 58.1 µs | 73.6 µs | 92.8 µs | 179,668 req/s |
| v0.5 Java fast path only | Unix socket | 64.1 µs | 85.6 µs | 117.2 µs | 151,404 req/s |
| v0.5 Java + borrowed Rust metadata | Unix socket | 61.1 µs | 84.3 µs | 99.9 µs | 164,852 req/s |
| v0.5.2, protocol v2 | Unix socket | 61.2 µs | 91.9 µs | 111.4 µs | 165,034 req/s |

The Java fast path reuses empty metadata, skips interceptor context creation when
there are no interceptors, writes mandatory protocol-v2 metadata directly into
the pooled frame, and reuses the virtual-thread factory. The Rust fast path fully
validates the metadata block through a borrowed view and materializes owned
metadata only when middleware needs it. Compared with the Java-only result, the
combined path improved median throughput by 7.9% on TCP and 8.9% on Unix sockets.
TCP p50/p95/p99 latency is lower than v0.4; Unix p50 remains within 3 µs. The
remaining throughput difference from v0.4 is 5-8% and includes protocol-v2's
mandatory metadata validation and server-side deadline enforcement.

The v0.5.2 rows are a fresh three-run median on the same Ryzen 5 2600X development host with
JDK 26 and Rust 1.98; the historical rows used JDK 24. Unix throughput remains level with the
previous optimized v0.5 measurement. TCP throughput varied more between runs and its median was
3.7% below that measurement, while all TCP latency percentiles remained below the v0.4 baseline.

This benchmark uses `moonlight-bridge-client` without the bundled runtime
instrumentation, so it is a transport baseline rather than a production-plugin simulation.

These are a regression baseline, not portable guarantees. Run
`./scripts/benchmark.sh` on the production CPU/kernel/JVM before tuning presets.

## Leaf plugin load test

`examples/paper-load-test-plugin` measures the shaded library as it is used by a
real plugin. A fresh three-run v0.5.2 measurement used Leaf 1.21.11 build 179,
JDK 21, loopback TCP, a release Rust backend, a Ryzen 5 2600X, and the default
embedded telemetry policy:

| Scenario | Requests | Concurrency | Throughput | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|---:|
| Sequential | 20,000 | 1 | 8,682 req/s | 104.2 µs | 175.8 µs | 214.1 µs |
| Pipelined | 1,000,000 | 128 | 112,268 req/s | 1.108 ms | 1.514 ms | 1.929 ms |

The pipelined runs ranged from 111,257 to 113,153 req/s. All six measured phases completed with
zero failures, and Leaf reported 20.0 TPS after the load. These numbers supersede the earlier
88,819-100,278 req/s development range; they remain machine-specific rather than a throughput
guarantee.

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
