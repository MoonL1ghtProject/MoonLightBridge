# moonlight-bridge-protocol

[![crates.io](https://img.shields.io/crates/v/moonlight-bridge-protocol.svg)](https://crates.io/crates/moonlight-bridge-protocol)
[![docs.rs](https://docs.rs/moonlight-bridge-protocol/badge.svg)](https://docs.rs/moonlight-bridge-protocol)
[![license](https://img.shields.io/crates/l/moonlight-bridge-protocol.svg)](https://github.com/MoonL1ghtProject/MoonLightBridge#license)

Runtime-independent wire primitives for
[MoonLightBridge](https://github.com/MoonL1ghtProject/MoonLightBridge), the typed bridge between
Java 21+ applications and Rust backends.

Most applications should use [`moonlight-bridge-server`](https://crates.io/crates/moonlight-bridge-server)
instead. This low-level crate is intended for alternate runtimes, diagnostics, protocol conformance
tests and tools that need to encode or inspect frames.

## Properties

- no async runtime and no socket I/O;
- fixed 24-byte big-endian header and opaque bounded body;
- strict header validation before body allocation;
- HELLO/WELCOME feature and limit negotiation;
- correlation IDs for out-of-order responses;
- optional deadline and distributed-trace metadata;
- structured protocol errors without a payload-codec dependency.

## Installation

```toml
[dependencies]
moonlight-bridge-protocol = "0.5.0"
```

Rust 1.88 or newer is required.

## Frame layout

| Offset | Size | Field | Meaning |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `MLBR` (`0x4D4C4252`) |
| 4 | 1 | version | Wire version, currently `2` |
| 5 | 1 | kind | `FrameKind` discriminant |
| 6 | 2 | flags | Bit 0 indicates a metadata prefix |
| 8 | 4 | body length | Unsigned payload length |
| 12 | 4 | method ID | Generated RPC/event ID |
| 16 | 8 | request ID | Connection-local correlation ID |

The default maximum body is 8 MiB and the default in-flight limit is 256. Peers negotiate the
minimum limits and feature intersection during handshake; runtimes must use negotiated values.

## Frame kinds

| Kind | Use |
|---|---|
| `Request` / `Response` / `Error` | RPC lifecycle |
| `Hello` / `Welcome` | Mandatory first exchange |
| `Cancel` | Abort an active request |
| `Ping` / `Pong` | Liveness |
| `Goodbye` | Graceful close |
| `Health` / `HealthStatus` | Built-in readiness |
| `Event` | One-way server-to-client event |

Request ID zero is reserved for connection-level frames and events. REQUEST/PING IDs must be
non-zero; duplicate active IDs are protocol errors at the runtime layer.

## Encoding and header validation

```rust
use moonlight_bridge_protocol::{Frame, FrameKind, HEADER_LEN};

let frame = Frame::new(FrameKind::Request, 0x1020_3040, 7, b"payload".to_vec());
let encoded = frame.encode()?;

let header: &[u8; HEADER_LEN] = encoded[..HEADER_LEN].try_into().unwrap();
let decoded = Frame::decode_header(header, 8 * 1024 * 1024)?;
assert_eq!(decoded.kind, FrameKind::Request);
assert_eq!(decoded.method_id, 0x1020_3040);
assert_eq!(decoded.request_id, 7);
# Ok::<(), moonlight_bridge_protocol::ProtocolError>(())
```

A streaming reader should read exactly `HEADER_LEN` bytes, validate with `decode_header`, then
allocate/read exactly `decoded.body_len`. Never allocate based on unchecked network bytes.
`Frame::encode_into` appends to an existing `Vec<u8>` for internal buffer reuse.

## Handshake and features

The client sends `Hello` first and the server returns `Welcome`. `PeerSettingsV2` carries:

- encoded/decoded body and metadata byte limits;
- in-flight call, concurrent stream, and initial-credit limits;
- supported compression codecs;
- transport and diagnostic feature masks.

Every non-zero limit is intersected with local policy. Codec and feature masks are intersected;
codec bit zero (`none`) is mandatory. A v1 frame is rejected rather than downgraded.

## Request metadata

`FLAG_HAS_METADATA` prefixes a body with a four-byte metadata length followed by canonical entries.
Each entry contains a critical numeric key, optional lowercase ASCII user-key name, value length,
and opaque value. Runtime keys include deadline, trace context, idempotency, authorization,
compression, retry, event, content-type, and diagnostic fields. Decoders reject duplicate singleton
keys, non-canonical order, unknown critical keys, malformed lengths, and configured count/byte-limit
violations before copying values. Transport runtimes remove the metadata prefix before dispatch.

Trace context is vendor-neutral wire data. This crate does not initialize or depend on an
observability SDK.

## Bounded compression

`CompressionPolicy` provides negotiated `none`/Zstandard encoding with a minimum payload threshold
and minimum required savings. Decoding checks the announced decoded length and expansion ratio
before invoking Zstandard. `DecodedByteBudget` supplies a process-shared admission limit; retain the
permit returned by `decode_with_reservation` for as long as the decoded request occupies runtime
memory. The defaults are a 1 KiB threshold, 64-byte minimum saving, 16 MiB decoded limit, ratio 64,
and Zstandard level 1.

Compression metadata is part of protocol v2 and must be used only after codec negotiation. See the
wire protocol document for the applicable frame kinds and required metadata pair.

## Errors and validation

`ErrorCode` includes the original six classifications plus `Unauthenticated`, `PermissionDenied`,
`Unavailable`, `CompressionFailure`, `ReplayGap`, `FailedPrecondition`, and
`UnsupportedProtocol`. An error body begins with a two-byte code followed by a bounded UTF-8
diagnostic message. Typed domain failures belong in Protobuf responses, not transport error strings.

Decoding rejects invalid magic, unsupported versions, unknown kinds, reserved flags, oversized
bodies, malformed settings/trace metadata, invalid sampling bytes and unknown error codes. Semantic
state checks—first-frame HELLO, duplicate IDs, response matching and negotiated-feature use—belong
to the client/server runtime.

See the complete [wire protocol document](https://github.com/MoonL1ghtProject/MoonLightBridge/blob/main/docs/protocol.md).

Licensed under MIT OR Apache-2.0.
