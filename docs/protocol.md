# MoonLightBridge wire protocol

Every frame consists of a fixed 24-byte header followed by `body_length` bytes.
All integers use network byte order (big-endian).

| Offset | Size | Field | Meaning |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `MLBR` (`0x4D4C4252`) |
| 4 | 1 | version | Protocol version, currently `2` |
| 5 | 1 | kind | Message kind; see below |
| 6 | 2 | flags | Bit 0: bounded metadata prefix |
| 8 | 4 | body length | Unsigned payload size |
| 12 | 4 | method ID | Stable ID assigned by the schema compiler |
| 16 | 8 | request ID | Connection-local correlation ID |

The maximum accepted body is 8 MiB by default. A peer must reject invalid
magic, unsupported versions, unknown frame kinds, reserved flags, and oversized
payloads before allocating the body buffer.

Request IDs allow responses to arrive in a different order from requests.
`request_id = 0` is reserved for connection-level frames.

## Handshake

The client must send `HELLO` as its first frame. The server replies with
`WELCOME`. Both contain a fixed 44-byte `PeerSettingsV2` body: maximum encoded body,
decoded body, metadata bytes, in-flight calls, concurrent streams, initial stream credit,
compression-codec bits, transport features, and diagnostic features. The first seven fields are
`u32`; the two feature masks are `u64`. The server selects minimum limits and intersects masks.
`none` compression (codec bit zero) is mandatory. There is no v1 fallback.

Feature bits negotiate deadlines (`1`), cancellation (`2`), heartbeat (`4`),
trace propagation (`8`), server events (`16`), health/readiness (`32`), and credit-based
server streaming (`64`).
The default timeout for the first HELLO frame is 10 seconds.

## Frame kinds

| Value | Kind | Use |
|---:|---|---|
| 1 | REQUEST | Invoke a method |
| 2 | RESPONSE | Successful result |
| 3 | ERROR | Structured failure |
| 16 | HELLO | Client handshake |
| 17 | WELCOME | Server handshake |
| 18 | CANCEL | Abort the matching request ID |
| 19/20 | PING/PONG | Liveness check with an echoed nonce |
| 21 | GOODBYE | Graceful disconnect |
| 22/23 | HEALTH/HEALTH_STATUS | Built-in readiness and load snapshot |
| 24 | EVENT | One-way Rust-to-Java event (`request_id = 0`) |
| 25 | STREAM_ITEM | One result belonging to a streaming request |
| 26 | STREAM_END | Successful end of a streaming request |
| 27 | STREAM_CREDIT | Client grants eight-byte unsigned item credit |

When flag bit 0 is set, the body begins with a `u32` metadata-block length. Each canonical entry is
`tagged_key: u16`, `name_length: u8`, `value_length: u32`, then name and value bytes. The high key
bit marks a critical entry. Runtime keys use numeric IDs; user entries use ID `0x7fff` and a
lowercase ASCII name. Runtime decoders remove this prefix before handler dispatch and enforce the
negotiated metadata byte limit plus local entry/value limits before copying values. Duplicate
singletons, non-canonical order, unknown critical keys, invalid user names, and overflowing lengths
are rejected.

Reserved keys carry deadlines, trace context, idempotency and authorization data, compression
state, retry identity, event cursors, content type, and application-owned diagnostic correlation.
Payloads and authorization values are never telemetry fields.

## RPC policies

Each RPC may declare `(moonlight.bridge.options.v1.rpc_policy)` in its `.proto` method options.
The policy contains the logical-call timeout, stream idle timeout, bounded retry and backoff,
idempotency class, decoded request and response limits, required authorization scopes, compression
mode, and trace sampling rate. Java and Rust generators compile these values into constants; the
runtime does not use Protobuf reflection on the request path.

The schema compiler rejects unsafe or ambiguous policies. Timeouts, message sizes, retry counts,
backoff values, scopes, and sampling rates must remain inside bounded ranges. Transparent retries
require an explicit read-only, idempotent, or idempotency-key declaration, and streaming calls
cannot enable transparent retry. A caller may tighten a generated deadline but cannot extend it.
Policy changes are recorded in `schema.lock` and require an intentional compatibility review.

## Compression

Codec bit `0` represents `none`; codec bit `1` represents Zstandard. An application frame may use
Zstandard only when bit `1` is present in the negotiated codec mask. Compression is limited to
`REQUEST`, `RESPONSE`, `EVENT`, and `STREAM_ITEM`; handshake, health, flow-control, terminal, and
error frames stay uncompressed so that connection recovery never depends on a codec.

A compressed body has the normal metadata prefix followed by the compressed payload. It must carry
both singleton metadata entries `compression-codec` (`u8`, value `1` for Zstandard) and
`original-length` (`u32`, big-endian). Supplying only one entry, using an unnegotiated codec, or
announcing a length above the negotiated decoded-body limit is a `COMPRESSION_FAILURE`.

The default runtimes consider payloads of at least 1 KiB, require a saving of at least 64 bytes,
use Zstandard level 1, and reject a decoded-to-encoded ratio above 64. They also share a bounded
decoded-byte admission budget across concurrent requests. Implementations must validate metadata,
the decoded limit, the ratio, and budget availability before decompression. Encoders keep the
original payload when compressed output would violate those same bounds, preventing frames that a
conforming peer would reject.

An error body begins with a two-byte code followed by a UTF-8 message. Codes are
`UNKNOWN_METHOD=1`, `INVALID_REQUEST=2`, `DEADLINE_EXCEEDED=3`, `CANCELLED=4`,
`RESOURCE_EXHAUSTED=5`, `INTERNAL=6`, `UNAUTHENTICATED=7`, `PERMISSION_DENIED=8`,
`UNAVAILABLE=9`, `COMPRESSION_FAILURE=10`, `REPLAY_GAP=11`, `FAILED_PRECONDITION=12`, and
`UNSUPPORTED_PROTOCOL=13`.

Duplicate active request IDs and zero IDs on REQUEST/PING are protocol errors.
Responses are accepted only when ID, method ID, and frame kind match the pending call.

## Server streaming

A server-streaming method starts with the same `REQUEST` frame as a unary method. The server
may keep at most one look-ahead item, but cannot send it until the client grants positive
`STREAM_CREDIT` for that request ID. Each `STREAM_ITEM` consumes one credit. `STREAM_END` completes
successfully; `ERROR`, `CANCEL`, a
deadline, or a disconnect terminates the stream. Credit frames use method ID zero and contain one
big-endian `u64`. This keeps memory bounded and prevents a slow stream consumer from blocking
unrelated multiplexed calls.

## Deliberately not specified

- payload codec;
- concrete payload codec;
- automatic endpoint discovery;
- the application-specific meaning of authorization scopes.

These features must extend the header semantics without changing its size.
