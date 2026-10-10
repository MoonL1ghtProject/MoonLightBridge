#![doc = include_str!("../README.md")]

use std::fmt;

mod compression;
mod metadata;

pub use compression::{
    CompressedPayload, CompressionCodec, CompressionError, CompressionPolicy, DecodedByteBudget,
    DecodedBytePermit,
};
pub use metadata::{Metadata, MetadataKey, MetadataLimits, ReservedMetadataKey, ValidatedMetadata};

/// ASCII `MLBR`, the MoonLightBridge wire signature.
pub const MAGIC: u32 = 0x4D4C_4252;
/// Current MoonLightBridge wire-protocol version.
pub const VERSION: u8 = 2;
/// Encoded frame-header length in bytes.
pub const HEADER_LEN: usize = 24;
/// Default maximum accepted frame body: 8 MiB.
pub const DEFAULT_MAX_BODY_LEN: u32 = 8 * 1024 * 1024;
/// Default number of concurrent requests permitted per connection.
pub const DEFAULT_MAX_IN_FLIGHT: u32 = 256;
/// Mandatory identity codec bit used during handshake.
pub const COMPRESSION_CODEC_NONE: u32 = 1;
/// Zstandard codec bit used during handshake.
pub const COMPRESSION_CODEC_ZSTD: u32 = 1 << 1;

/// Indicates that a frame body starts with a bounded metadata block.
pub const FLAG_HAS_METADATA: u16 = 1;
/// Bit mask containing every flag understood by this protocol version.
pub const KNOWN_FLAGS: u16 = FLAG_HAS_METADATA;
/// Encoded trace-context length in bytes.
pub const TRACE_CONTEXT_LEN: usize = 25;

/// Peer supports transmitting and enforcing request deadlines.
pub const FEATURE_DEADLINES: u64 = 1;
/// Peer supports cancelling active requests.
pub const FEATURE_CANCELLATION: u64 = 1 << 1;
/// Peer supports PING/PONG liveness checks.
pub const FEATURE_HEARTBEAT: u64 = 1 << 2;
/// Peer supports vendor-neutral distributed-trace context.
pub const FEATURE_TRACE_CONTEXT: u64 = 1 << 3;
/// Peer supports one-way server-to-client events.
pub const FEATURE_SERVER_EVENTS: u64 = 1 << 4;
/// Peer supports the built-in health request.
pub const FEATURE_HEALTH: u64 = 1 << 5;
/// Peer supports credit-based server-streaming RPCs.
pub const FEATURE_SERVER_STREAMING: u64 = 1 << 6;
/// Complete feature set implemented by the bundled server runtime.
pub const SERVER_FEATURES: u64 = FEATURE_DEADLINES
    | FEATURE_CANCELLATION
    | FEATURE_HEARTBEAT
    | FEATURE_TRACE_CONTEXT
    | FEATURE_SERVER_EVENTS
    | FEATURE_HEALTH
    | FEATURE_SERVER_STREAMING;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
/// Vendor-neutral trace identifiers propagated with an RPC request.
pub struct TraceContext {
    /// Sixteen-byte distributed trace identifier.
    pub trace_id: [u8; 16],
    /// Eight-byte identifier of the client span that issued the request.
    pub parent_span_id: [u8; 8],
    /// Whether the originating trace was selected for recording.
    pub sampled: bool,
}

impl TraceContext {
    /// Appends the fixed-width representation to `output` without clearing it.
    pub fn encode_into(self, output: &mut Vec<u8>) {
        output.extend_from_slice(&self.trace_id);
        output.extend_from_slice(&self.parent_span_id);
        output.push(u8::from(self.sampled));
    }

    /// Decodes a trace context from the first [`TRACE_CONTEXT_LEN`] input bytes.
    pub fn decode(input: &[u8]) -> Result<Self, ProtocolError> {
        if input.len() < TRACE_CONTEXT_LEN {
            return Err(ProtocolError::InvalidTraceContextLength(input.len()));
        }
        let sampled = match input[24] {
            0 => false,
            1 => true,
            value => return Err(ProtocolError::InvalidTraceSampling(value)),
        };
        Ok(Self {
            trace_id: input[0..16].try_into().unwrap(),
            parent_span_id: input[16..24].try_into().unwrap(),
            sampled,
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
/// Type discriminator stored in every frame header.
pub enum FrameKind {
    /// Client-to-server RPC request.
    Request = 1,
    /// Successful server-to-client RPC response.
    Response = 2,
    /// Structured server-to-client RPC failure.
    Error = 3,
    /// Mandatory client handshake frame.
    Hello = 16,
    /// Mandatory server handshake response.
    Welcome = 17,
    /// Client request to abort an active RPC.
    Cancel = 18,
    /// Client liveness probe.
    Ping = 19,
    /// Server response to a liveness probe.
    Pong = 20,
    /// Graceful connection shutdown notification.
    Goodbye = 21,
    /// Client request for a runtime health snapshot.
    Health = 22,
    /// Server response containing a runtime health snapshot.
    HealthStatus = 23,
    /// One-way server-to-client application event.
    Event = 24,
    /// One item produced by a server-streaming RPC.
    StreamItem = 25,
    /// Successful end of a server-streaming RPC.
    StreamEnd = 26,
    /// Client-to-server delivery-credit update for a stream.
    StreamCredit = 27,
    /// Server admission-stop notification; existing calls may still complete.
    GoAway = 28,
}

impl TryFrom<u8> for FrameKind {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, ProtocolError> {
        match value {
            1 => Ok(Self::Request),
            2 => Ok(Self::Response),
            3 => Ok(Self::Error),
            16 => Ok(Self::Hello),
            17 => Ok(Self::Welcome),
            18 => Ok(Self::Cancel),
            19 => Ok(Self::Ping),
            20 => Ok(Self::Pong),
            21 => Ok(Self::Goodbye),
            22 => Ok(Self::Health),
            23 => Ok(Self::HealthStatus),
            24 => Ok(Self::Event),
            25 => Ok(Self::StreamItem),
            26 => Ok(Self::StreamEnd),
            27 => Ok(Self::StreamCredit),
            28 => Ok(Self::GoAway),
            other => Err(ProtocolError::UnknownKind(other)),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
/// One complete protocol frame with an owned opaque body.
pub struct Frame {
    /// Frame type.
    pub kind: FrameKind,
    /// Optional metadata flags; unknown bits are rejected during encoding.
    pub flags: u16,
    /// Generated RPC or event identifier, or zero for connection-level frames.
    pub method_id: u32,
    /// Non-zero correlation identifier for requests, responses and probes.
    pub request_id: u64,
    /// Opaque payload and optional metadata prefixes.
    pub body: Vec<u8>,
}

impl Frame {
    /// Creates a frame with no optional flags.
    pub fn new(kind: FrameKind, method_id: u32, request_id: u64, body: Vec<u8>) -> Self {
        Self {
            kind,
            flags: 0,
            method_id,
            request_id,
            body,
        }
    }

    /// Replaces the frame flag bit mask.
    pub fn with_flags(mut self, flags: u16) -> Self {
        self.flags = flags;
        self
    }

    /// Encodes the complete frame into a newly allocated byte vector.
    pub fn encode(&self) -> Result<Vec<u8>, ProtocolError> {
        let mut output = Vec::with_capacity(HEADER_LEN + self.body.len());
        self.encode_into(&mut output)?;
        Ok(output)
    }

    /// Appends the encoded frame to `output`, enabling caller-managed buffer reuse.
    pub fn encode_into(&self, output: &mut Vec<u8>) -> Result<(), ProtocolError> {
        if self.flags & !KNOWN_FLAGS != 0 {
            return Err(ProtocolError::UnsupportedFlags(self.flags));
        }
        let body_len =
            u32::try_from(self.body.len()).map_err(|_| ProtocolError::BodyTooLarge(u32::MAX))?;
        output.extend_from_slice(&MAGIC.to_be_bytes());
        output.push(VERSION);
        output.push(self.kind as u8);
        output.extend_from_slice(&self.flags.to_be_bytes());
        output.extend_from_slice(&body_len.to_be_bytes());
        output.extend_from_slice(&self.method_id.to_be_bytes());
        output.extend_from_slice(&self.request_id.to_be_bytes());
        output.extend_from_slice(&self.body);
        Ok(())
    }

    /// Validates and decodes a fixed-width header before its body is allocated.
    pub fn decode_header(
        header: &[u8; HEADER_LEN],
        max_body_len: u32,
    ) -> Result<DecodedHeader, ProtocolError> {
        let magic = u32::from_be_bytes(header[0..4].try_into().unwrap());
        if magic != MAGIC {
            return Err(ProtocolError::InvalidMagic(magic));
        }
        if header[4] != VERSION {
            return Err(ProtocolError::UnsupportedVersion(header[4]));
        }
        let kind = FrameKind::try_from(header[5])?;
        let flags = u16::from_be_bytes(header[6..8].try_into().unwrap());
        if flags & !KNOWN_FLAGS != 0 {
            return Err(ProtocolError::UnsupportedFlags(flags));
        }
        let body_len = u32::from_be_bytes(header[8..12].try_into().unwrap());
        if body_len > max_body_len {
            return Err(ProtocolError::BodyTooLarge(body_len));
        }
        Ok(DecodedHeader {
            kind,
            flags,
            body_len,
            method_id: u32::from_be_bytes(header[12..16].try_into().unwrap()),
            request_id: u64::from_be_bytes(header[16..24].try_into().unwrap()),
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
/// Validated header fields returned independently from the frame body.
pub struct DecodedHeader {
    /// Frame type.
    pub kind: FrameKind,
    /// Optional metadata flags.
    pub flags: u16,
    /// Number of body bytes that follow the header.
    pub body_len: u32,
    /// RPC or event identifier.
    pub method_id: u32,
    /// Connection-local correlation identifier.
    pub request_id: u64,
}

/// Limits and capabilities exchanged by protocol-v2 HELLO/WELCOME frames.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PeerSettingsV2 {
    /// Largest encoded frame body accepted by the peer.
    pub max_body_len: u32,
    /// Largest decoded application payload accepted by the peer.
    pub max_decoded_body_len: u32,
    /// Largest metadata block accepted by the peer.
    pub max_metadata_len: u32,
    /// Largest number of concurrent calls accepted by the peer.
    pub max_in_flight: u32,
    /// Largest number of concurrent streams accepted by the peer.
    pub max_concurrent_streams: u32,
    /// Initial item credit granted for each stream direction.
    pub initial_stream_credit: u32,
    /// Bit set of supported compression codecs; bit zero is the mandatory `none` codec.
    pub compression_codecs: u32,
    /// General protocol capability bits.
    pub features: u64,
    /// Optional diagnostic protocol capability bits.
    pub diagnostic_features: u64,
}

impl PeerSettingsV2 {
    /// Fixed encoded settings-body length in bytes.
    pub const BODY_LEN: usize = 44;

    /// Encodes every negotiated field in fixed-width network-byte order.
    pub fn encode(self) -> Vec<u8> {
        let mut body = Vec::with_capacity(Self::BODY_LEN);
        body.extend_from_slice(&self.max_body_len.to_be_bytes());
        body.extend_from_slice(&self.max_decoded_body_len.to_be_bytes());
        body.extend_from_slice(&self.max_metadata_len.to_be_bytes());
        body.extend_from_slice(&self.max_in_flight.to_be_bytes());
        body.extend_from_slice(&self.max_concurrent_streams.to_be_bytes());
        body.extend_from_slice(&self.initial_stream_credit.to_be_bytes());
        body.extend_from_slice(&self.compression_codecs.to_be_bytes());
        body.extend_from_slice(&self.features.to_be_bytes());
        body.extend_from_slice(&self.diagnostic_features.to_be_bytes());
        body
    }

    /// Decodes an exact-length v2 settings body and rejects zero safety limits.
    pub fn decode(body: &[u8]) -> Result<Self, ProtocolError> {
        if body.len() != Self::BODY_LEN {
            return Err(ProtocolError::InvalidSettingsLength(body.len()));
        }
        let settings = Self {
            max_body_len: u32::from_be_bytes(body[0..4].try_into().unwrap()),
            max_decoded_body_len: u32::from_be_bytes(body[4..8].try_into().unwrap()),
            max_metadata_len: u32::from_be_bytes(body[8..12].try_into().unwrap()),
            max_in_flight: u32::from_be_bytes(body[12..16].try_into().unwrap()),
            max_concurrent_streams: u32::from_be_bytes(body[16..20].try_into().unwrap()),
            initial_stream_credit: u32::from_be_bytes(body[20..24].try_into().unwrap()),
            compression_codecs: u32::from_be_bytes(body[24..28].try_into().unwrap()),
            features: u64::from_be_bytes(body[28..36].try_into().unwrap()),
            diagnostic_features: u64::from_be_bytes(body[36..44].try_into().unwrap()),
        };
        if settings.max_body_len == 0
            || settings.max_decoded_body_len == 0
            || settings.max_metadata_len == 0
            || settings.max_in_flight == 0
            || settings.max_concurrent_streams == 0
            || settings.initial_stream_credit == 0
            || settings.compression_codecs & 1 == 0
        {
            return Err(ProtocolError::InvalidSettingsValue);
        }
        Ok(settings)
    }
}

impl Default for PeerSettingsV2 {
    fn default() -> Self {
        Self {
            max_body_len: DEFAULT_MAX_BODY_LEN,
            max_decoded_body_len: 16 * 1024 * 1024,
            max_metadata_len: 16 * 1024,
            max_in_flight: DEFAULT_MAX_IN_FLIGHT,
            max_concurrent_streams: 64,
            initial_stream_credit: 32,
            compression_codecs: COMPRESSION_CODEC_NONE | COMPRESSION_CODEC_ZSTD,
            features: SERVER_FEATURES,
            diagnostic_features: 0,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u16)]
/// Transport-level RPC error classification encoded in an ERROR frame.
pub enum ErrorCode {
    /// No handler is registered for the requested method ID.
    UnknownMethod = 1,
    /// The request payload or metadata is invalid.
    InvalidRequest = 2,
    /// The request did not complete before its deadline.
    DeadlineExceeded = 3,
    /// The caller cancelled the request.
    Cancelled = 4,
    /// A concurrency or queue limit rejected the request.
    ResourceExhausted = 5,
    /// The handler failed unexpectedly.
    Internal = 6,
    /// Authentication credentials are absent or invalid.
    Unauthenticated = 7,
    /// The authenticated caller lacks a required permission.
    PermissionDenied = 8,
    /// The destination is unavailable or draining.
    Unavailable = 9,
    /// Compression or decompression validation failed.
    CompressionFailure = 10,
    /// Requested event history is no longer available.
    ReplayGap = 11,
    /// The operation cannot run in the current state.
    FailedPrecondition = 12,
    /// The peer uses an unsupported protocol capability or version.
    UnsupportedProtocol = 13,
}

impl ErrorCode {
    /// Encodes this code followed by a UTF-8 diagnostic message.
    pub fn encode(self, message: &str) -> Vec<u8> {
        let mut body = Vec::with_capacity(2 + message.len());
        body.extend_from_slice(&(self as u16).to_be_bytes());
        body.extend_from_slice(message.as_bytes());
        body
    }
}

impl TryFrom<u16> for ErrorCode {
    type Error = ProtocolError;

    fn try_from(value: u16) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::UnknownMethod),
            2 => Ok(Self::InvalidRequest),
            3 => Ok(Self::DeadlineExceeded),
            4 => Ok(Self::Cancelled),
            5 => Ok(Self::ResourceExhausted),
            6 => Ok(Self::Internal),
            7 => Ok(Self::Unauthenticated),
            8 => Ok(Self::PermissionDenied),
            9 => Ok(Self::Unavailable),
            10 => Ok(Self::CompressionFailure),
            11 => Ok(Self::ReplayGap),
            12 => Ok(Self::FailedPrecondition),
            13 => Ok(Self::UnsupportedProtocol),
            other => Err(ProtocolError::UnknownErrorCode(other)),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
/// Error produced while decoding or encoding protocol primitives.
pub enum ProtocolError {
    /// The four-byte wire signature did not match [`MAGIC`].
    InvalidMagic(u32),
    /// The peer uses a wire version this crate does not implement.
    UnsupportedVersion(u8),
    /// The frame-kind discriminant is unknown.
    UnknownKind(u8),
    /// One or more flag bits are not defined by this version.
    UnsupportedFlags(u16),
    /// The announced body length exceeds the configured maximum.
    BodyTooLarge(u32),
    /// A handshake settings body has the wrong length.
    InvalidSettingsLength(usize),
    /// A negotiated size or concurrency limit is zero.
    InvalidSettingsValue,
    /// A trace-context prefix is shorter than [`TRACE_CONTEXT_LEN`].
    InvalidTraceContextLength(usize),
    /// The trace sampling byte is neither zero nor one.
    InvalidTraceSampling(u8),
    /// The metadata block length exceeds the bytes available in this body.
    InvalidMetadataLength {
        /// Length declared by the prefix.
        announced: u32,
        /// Bytes remaining after the prefix.
        available: usize,
    },
    /// The metadata block exceeds the configured encoded-byte limit.
    MetadataTooLarge(u32),
    /// A metadata entry exceeds the configured value-byte limit.
    MetadataValueTooLarge(u32),
    /// The metadata block contains more entries than configured.
    TooManyMetadataEntries(u16),
    /// Metadata entry lengths overflow or exceed the enclosing block.
    InvalidMetadataEntryLength,
    /// A singleton metadata key occurs more than once.
    DuplicateMetadataKey,
    /// Metadata entries are not in canonical key order.
    NonCanonicalMetadataOrder,
    /// A user metadata key is not valid lowercase ASCII.
    InvalidUserMetadataKey,
    /// A critical numeric metadata key is unknown to this implementation.
    UnknownCriticalMetadataKey(u16),
    /// An ERROR frame contains an unknown stable error code.
    UnknownErrorCode(u16),
}

impl fmt::Display for ProtocolError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for ProtocolError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frame_round_trip_header() {
        let encoded = Frame::new(FrameKind::Request, 42, 99, b"hello".to_vec())
            .with_flags(FLAG_HAS_METADATA)
            .encode()
            .unwrap();
        let header: &[u8; HEADER_LEN] = encoded[..HEADER_LEN].try_into().unwrap();
        let decoded = Frame::decode_header(header, DEFAULT_MAX_BODY_LEN).unwrap();
        assert_eq!(decoded.kind, FrameKind::Request);
        assert_eq!(decoded.flags, FLAG_HAS_METADATA);
        assert_eq!(decoded.method_id, 42);
        assert_eq!(decoded.request_id, 99);
        assert_eq!(decoded.body_len, 5);
    }

    #[test]
    fn peer_settings_round_trip() {
        let expected = PeerSettingsV2 {
            max_body_len: 1024,
            max_in_flight: 32,
            features: SERVER_FEATURES,
            ..PeerSettingsV2::default()
        };
        assert_eq!(
            PeerSettingsV2::decode(&expected.encode()).unwrap(),
            expected
        );
    }

    #[test]
    fn trace_context_round_trip() {
        let expected = TraceContext {
            trace_id: [7; 16],
            parent_span_id: [9; 8],
            sampled: true,
        };
        let mut encoded = Vec::new();
        expected.encode_into(&mut encoded);
        assert_eq!(TraceContext::decode(&encoded).unwrap(), expected);
    }

    #[test]
    fn oversized_body_is_rejected_from_header() {
        let encoded = Frame::new(FrameKind::Request, 1, 1, vec![0; 16])
            .encode()
            .unwrap();
        let header: &[u8; HEADER_LEN] = encoded[..HEADER_LEN].try_into().unwrap();
        assert_eq!(
            Frame::decode_header(header, 8),
            Err(ProtocolError::BodyTooLarge(16))
        );
    }
}
