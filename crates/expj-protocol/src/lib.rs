use std::fmt;

pub const MAGIC: u32 = 0x4558_504A;
pub const VERSION: u8 = 1;
pub const HEADER_LEN: usize = 24;
pub const DEFAULT_MAX_BODY_LEN: u32 = 8 * 1024 * 1024;
pub const DEFAULT_MAX_IN_FLIGHT: u32 = 256;

pub const FLAG_HAS_DEADLINE: u16 = 1;
pub const FLAG_HAS_TRACE_CONTEXT: u16 = 1 << 1;
pub const KNOWN_FLAGS: u16 = FLAG_HAS_DEADLINE | FLAG_HAS_TRACE_CONTEXT;
pub const TRACE_CONTEXT_LEN: usize = 25;

pub const FEATURE_DEADLINES: u64 = 1;
pub const FEATURE_CANCELLATION: u64 = 1 << 1;
pub const FEATURE_HEARTBEAT: u64 = 1 << 2;
pub const FEATURE_TRACE_CONTEXT: u64 = 1 << 3;
pub const SERVER_FEATURES: u64 =
    FEATURE_DEADLINES | FEATURE_CANCELLATION | FEATURE_HEARTBEAT | FEATURE_TRACE_CONTEXT;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TraceContext {
    pub trace_id: [u8; 16],
    pub parent_span_id: [u8; 8],
    pub sampled: bool,
}

impl TraceContext {
    pub fn encode_into(self, output: &mut Vec<u8>) {
        output.extend_from_slice(&self.trace_id);
        output.extend_from_slice(&self.parent_span_id);
        output.push(u8::from(self.sampled));
    }

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
pub enum FrameKind {
    Request = 1,
    Response = 2,
    Error = 3,
    Hello = 16,
    Welcome = 17,
    Cancel = 18,
    Ping = 19,
    Pong = 20,
    Goodbye = 21,
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
            other => Err(ProtocolError::UnknownKind(other)),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub kind: FrameKind,
    pub flags: u16,
    pub method_id: u32,
    pub request_id: u64,
    pub body: Vec<u8>,
}

impl Frame {
    pub fn new(kind: FrameKind, method_id: u32, request_id: u64, body: Vec<u8>) -> Self {
        Self {
            kind,
            flags: 0,
            method_id,
            request_id,
            body,
        }
    }

    pub fn with_flags(mut self, flags: u16) -> Self {
        self.flags = flags;
        self
    }

    pub fn encode(&self) -> Result<Vec<u8>, ProtocolError> {
        let mut output = Vec::with_capacity(HEADER_LEN + self.body.len());
        self.encode_into(&mut output)?;
        Ok(output)
    }

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
pub struct DecodedHeader {
    pub kind: FrameKind,
    pub flags: u16,
    pub body_len: u32,
    pub method_id: u32,
    pub request_id: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PeerSettings {
    pub max_body_len: u32,
    pub max_in_flight: u32,
    pub features: u64,
}

impl PeerSettings {
    pub const BODY_LEN: usize = 16;

    pub fn encode(self) -> Vec<u8> {
        let mut body = Vec::with_capacity(Self::BODY_LEN);
        body.extend_from_slice(&self.max_body_len.to_be_bytes());
        body.extend_from_slice(&self.max_in_flight.to_be_bytes());
        body.extend_from_slice(&self.features.to_be_bytes());
        body
    }

    pub fn decode(body: &[u8]) -> Result<Self, ProtocolError> {
        if body.len() != Self::BODY_LEN {
            return Err(ProtocolError::InvalidSettingsLength(body.len()));
        }
        let settings = Self {
            max_body_len: u32::from_be_bytes(body[0..4].try_into().unwrap()),
            max_in_flight: u32::from_be_bytes(body[4..8].try_into().unwrap()),
            features: u64::from_be_bytes(body[8..16].try_into().unwrap()),
        };
        if settings.max_body_len == 0 || settings.max_in_flight == 0 {
            return Err(ProtocolError::InvalidSettingsValue);
        }
        Ok(settings)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u16)]
pub enum ErrorCode {
    UnknownMethod = 1,
    InvalidRequest = 2,
    DeadlineExceeded = 3,
    Cancelled = 4,
    ResourceExhausted = 5,
    Internal = 6,
}

impl ErrorCode {
    pub fn encode(self, message: &str) -> Vec<u8> {
        let mut body = Vec::with_capacity(2 + message.len());
        body.extend_from_slice(&(self as u16).to_be_bytes());
        body.extend_from_slice(message.as_bytes());
        body
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProtocolError {
    InvalidMagic(u32),
    UnsupportedVersion(u8),
    UnknownKind(u8),
    UnsupportedFlags(u16),
    BodyTooLarge(u32),
    InvalidSettingsLength(usize),
    InvalidSettingsValue,
    InvalidTraceContextLength(usize),
    InvalidTraceSampling(u8),
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
            .with_flags(FLAG_HAS_DEADLINE)
            .encode()
            .unwrap();
        let header: &[u8; HEADER_LEN] = encoded[..HEADER_LEN].try_into().unwrap();
        let decoded = Frame::decode_header(header, DEFAULT_MAX_BODY_LEN).unwrap();
        assert_eq!(decoded.kind, FrameKind::Request);
        assert_eq!(decoded.flags, FLAG_HAS_DEADLINE);
        assert_eq!(decoded.method_id, 42);
        assert_eq!(decoded.request_id, 99);
        assert_eq!(decoded.body_len, 5);
    }

    #[test]
    fn peer_settings_round_trip() {
        let expected = PeerSettings {
            max_body_len: 1024,
            max_in_flight: 32,
            features: SERVER_FEATURES,
        };
        assert_eq!(PeerSettings::decode(&expected.encode()).unwrap(), expected);
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
