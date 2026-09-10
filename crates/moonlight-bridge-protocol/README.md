# moonlight-bridge-protocol

[![crates.io](https://img.shields.io/crates/v/moonlight-bridge-protocol.svg)](https://crates.io/crates/moonlight-bridge-protocol)
[![docs.rs](https://docs.rs/moonlight-bridge-protocol/badge.svg)](https://docs.rs/moonlight-bridge-protocol)
[![license](https://img.shields.io/crates/l/moonlight-bridge-protocol.svg)](https://github.com/MoonL1ghtProject/MoonLightBridge#license)

Runtime-independent wire primitives for
[MoonLightBridge](https://github.com/MoonL1ghtProject/MoonLightBridge), the typed bridge between Java
Minecraft plugins and Rust backends.

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
moonlight-bridge-protocol = "0.1.1"
```

Rust 1.88 or newer is required.

## Frame layout

| Offset | Size | Field | Meaning |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `MLBR` (`0x4D4C4252`) |
| 4 | 1 | version | Wire version, currently `1` |
| 5 | 1 | kind | `FrameKind` discriminant |
| 6 | 2 | flags | Deadline and trace-context bits |
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

The client sends `Hello` first and the server returns `Welcome`. `PeerSettings` carries:

- `max_body_len: u32`;
- `max_in_flight: u32`;
- `features: u64`.

Feature bits negotiate deadlines, cancellation, heartbeat, trace context, server events and health.
Sending a feature's frame or flag without negotiation is invalid.

## Request metadata

`FLAG_HAS_DEADLINE` prefixes a request body with a four-byte timeout in milliseconds.
`FLAG_HAS_TRACE_CONTEXT` adds 16 trace-ID bytes, 8 parent-span-ID bytes and one sampled byte.
Transport runtimes remove these prefixes before handler dispatch.

Trace context is vendor-neutral wire data. This crate does not initialize or depend on Sentry or
another observability SDK.

## Errors and validation

`ErrorCode` includes `UnknownMethod`, `InvalidRequest`, `DeadlineExceeded`, `Cancelled`,
`ResourceExhausted` and `Internal`. An error body begins with a two-byte code followed by a UTF-8
diagnostic message. Typed domain failures belong in Protobuf responses, not transport error strings.

Decoding rejects invalid magic, unsupported versions, unknown kinds, reserved flags, oversized
bodies, malformed settings/trace metadata, invalid sampling bytes and unknown error codes. Semantic
state checks—first-frame HELLO, duplicate IDs, response matching and negotiated-feature use—belong
to the client/server runtime.

See the complete [wire protocol document](https://github.com/MoonL1ghtProject/MoonLightBridge/blob/main/docs/protocol.md).

Licensed under MIT OR Apache-2.0.
