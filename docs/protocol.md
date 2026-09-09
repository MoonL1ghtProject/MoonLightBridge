# EXPJ wire protocol — draft 0

Every frame consists of a fixed 24-byte header followed by `body_length` bytes.
All integers use network byte order (big-endian).

| Offset | Size | Field | Meaning |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `EXPJ` (`0x4558504A`) |
| 4 | 1 | version | Protocol version, currently `1` |
| 5 | 1 | kind | Message kind; see below |
| 6 | 2 | flags | Bit 0: deadline; bit 1: distributed trace context |
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
`WELCOME`. Both contain three big-endian settings: `max_body_length: u32`,
`max_in_flight: u32`, and `features: u64`. The server selects the minimum limits
and the intersection of feature bits.

Feature bits currently negotiate deadlines (`1`), cancellation (`2`),
heartbeat (`4`), and trace-context propagation (`8`).

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

When flag bit 0 is set, a request body starts with a four-byte timeout in
milliseconds. This prefix is transport metadata and is removed before handler
dispatch. A timeout results in error code `DEADLINE_EXCEEDED`; a client-side
timeout also emits `CANCEL` so work can be aborted promptly.

When flag bit 1 is set, the deadline (when present) is followed by a 25-byte
trace context: 16 bytes of trace ID, 8 bytes of parent span ID, and one sampling
byte (`0` or `1`). It is sent only when the feature was negotiated. This compact
layout is compatible with Sentry trace IDs but does not make the core protocol
depend on a particular observability vendor.

An error body begins with a two-byte code followed by a UTF-8 message. Codes are
`UNKNOWN_METHOD=1`, `INVALID_REQUEST=2`, `DEADLINE_EXCEEDED=3`, `CANCELLED=4`,
`RESOURCE_EXHAUSTED=5`, and `INTERNAL=6`.

## Deliberately not specified in M0

- payload codec;
- events and streams;
- compression;
- authentication.

These features must extend the header semantics without changing its size.
