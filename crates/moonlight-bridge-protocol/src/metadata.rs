use std::collections::BTreeMap;

use crate::ProtocolError;

const CRITICAL_BIT: u16 = 1 << 15;
const KEY_MASK: u16 = !CRITICAL_BIT;
const USER_KEY_ID: u16 = 0x7fff;
const ENTRY_HEADER_LEN: usize = 7;

/// Local limits applied before metadata values are allocated or copied.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MetadataLimits {
    /// Maximum encoded metadata-block length, excluding its four-byte prefix.
    pub max_bytes: u32,
    /// Maximum number of entries in one metadata block.
    pub max_entries: u16,
    /// Maximum value length for one entry.
    pub max_value_bytes: u32,
}

impl Default for MetadataLimits {
    fn default() -> Self {
        Self {
            max_bytes: 16 * 1024,
            max_entries: 64,
            max_value_bytes: 8 * 1024,
        }
    }
}

/// Runtime-owned metadata keys with stable wire identifiers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
#[repr(u16)]
pub enum ReservedMetadataKey {
    /// Relative request deadline in milliseconds.
    DeadlineMillis = 1,
    /// Vendor-neutral distributed trace context.
    TraceContext = 2,
    /// Stable idempotency key for safe retry.
    IdempotencyKey = 3,
    /// Opaque authorization value consumed by middleware.
    Authorization = 4,
    /// Generated list of required authorization scopes.
    RequiredScopes = 5,
    /// Negotiated compression codec.
    CompressionCodec = 6,
    /// Uncompressed payload length.
    OriginalLength = 7,
    /// Stable logical call identifier shared across attempts.
    LogicalCallId = 8,
    /// Zero-based retry attempt number.
    RetryAttempt = 9,
    /// Event resume cursor.
    EventCursor = 10,
    /// Event sequence number.
    EventSequence = 11,
    /// Application content type.
    ContentType = 12,
    /// Anonymous diagnostic correlation identifier.
    DiagnosticCorrelation = 13,
}

impl TryFrom<u16> for ReservedMetadataKey {
    type Error = ();

    fn try_from(value: u16) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::DeadlineMillis),
            2 => Ok(Self::TraceContext),
            3 => Ok(Self::IdempotencyKey),
            4 => Ok(Self::Authorization),
            5 => Ok(Self::RequiredScopes),
            6 => Ok(Self::CompressionCodec),
            7 => Ok(Self::OriginalLength),
            8 => Ok(Self::LogicalCallId),
            9 => Ok(Self::RetryAttempt),
            10 => Ok(Self::EventCursor),
            11 => Ok(Self::EventSequence),
            12 => Ok(Self::ContentType),
            13 => Ok(Self::DiagnosticCorrelation),
            _ => Err(()),
        }
    }
}

/// A validated metadata key.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub enum MetadataKey {
    /// Runtime-owned numeric key.
    Reserved(ReservedMetadataKey),
    /// Lowercase ASCII application key.
    User(String),
}

/// Canonically ordered metadata attached to one frame body.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Metadata {
    entries: BTreeMap<MetadataKey, Vec<u8>>,
}

impl Metadata {
    /// Creates an empty metadata block.
    pub fn new() -> Self {
        Self::default()
    }

    /// Returns whether the block contains no entries.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Adds one runtime-owned singleton value.
    pub fn insert_reserved(
        &mut self,
        key: ReservedMetadataKey,
        value: Vec<u8>,
    ) -> Result<(), ProtocolError> {
        self.insert(MetadataKey::Reserved(key), value)
    }

    /// Adds one application metadata value after validating its lowercase ASCII key.
    pub fn insert_user(
        &mut self,
        name: impl Into<String>,
        value: Vec<u8>,
    ) -> Result<(), ProtocolError> {
        let name = name.into();
        validate_user_key(&name)?;
        self.insert(MetadataKey::User(name), value)
    }

    fn insert(&mut self, key: MetadataKey, value: Vec<u8>) -> Result<(), ProtocolError> {
        if self.entries.contains_key(&key) {
            return Err(ProtocolError::DuplicateMetadataKey);
        }
        self.entries.insert(key, value);
        Ok(())
    }

    /// Returns the raw value for a validated key.
    pub fn get(&self, key: &MetadataKey) -> Option<&[u8]> {
        self.entries.get(key).map(Vec::as_slice)
    }

    /// Appends a length-prefixed canonical metadata block without clearing `output`.
    pub fn encode_prefix(&self, output: &mut Vec<u8>) -> Result<(), ProtocolError> {
        let encoded_len = self
            .entries
            .iter()
            .try_fold(0_usize, |total, (key, value)| {
                let name_len = match key {
                    MetadataKey::Reserved(_) => 0,
                    MetadataKey::User(name) => name.len(),
                };
                total
                    .checked_add(ENTRY_HEADER_LEN)
                    .and_then(|length| length.checked_add(name_len))
                    .and_then(|length| length.checked_add(value.len()))
                    .ok_or(ProtocolError::InvalidMetadataEntryLength)
            })?;
        let encoded_len =
            u32::try_from(encoded_len).map_err(|_| ProtocolError::MetadataTooLarge(u32::MAX))?;
        output.extend_from_slice(&encoded_len.to_be_bytes());

        for (key, value) in &self.entries {
            let (tagged_id, name) = match key {
                MetadataKey::Reserved(key) => (CRITICAL_BIT | *key as u16, ""),
                MetadataKey::User(name) => (USER_KEY_ID, name.as_str()),
            };
            let name_len =
                u8::try_from(name.len()).map_err(|_| ProtocolError::InvalidUserMetadataKey)?;
            let value_len = u32::try_from(value.len())
                .map_err(|_| ProtocolError::InvalidMetadataEntryLength)?;
            output.extend_from_slice(&tagged_id.to_be_bytes());
            output.push(name_len);
            output.extend_from_slice(&value_len.to_be_bytes());
            output.extend_from_slice(name.as_bytes());
            output.extend_from_slice(value);
        }
        Ok(())
    }

    /// Decodes a bounded metadata prefix and returns the remaining application payload.
    pub fn decode(input: &[u8], limits: MetadataLimits) -> Result<(Self, &[u8]), ProtocolError> {
        let length_bytes = input.get(..4).ok_or(ProtocolError::InvalidMetadataLength {
            announced: 0,
            available: input.len(),
        })?;
        let announced = u32::from_be_bytes(length_bytes.try_into().unwrap());
        let available = input.len() - 4;
        let block_len = usize::try_from(announced).unwrap_or(usize::MAX);
        if block_len > available {
            return Err(ProtocolError::InvalidMetadataLength {
                announced,
                available,
            });
        }
        if announced > limits.max_bytes {
            return Err(ProtocolError::MetadataTooLarge(announced));
        }

        let block = &input[4..4 + block_len];
        let mut position = 0_usize;
        let mut count = 0_u16;
        let mut metadata = Self::new();
        let mut previous_order: Option<(u16, Vec<u8>)> = None;

        while position < block.len() {
            count = count
                .checked_add(1)
                .ok_or(ProtocolError::TooManyMetadataEntries(u16::MAX))?;
            if count > limits.max_entries {
                return Err(ProtocolError::TooManyMetadataEntries(count));
            }
            let header_end = position
                .checked_add(ENTRY_HEADER_LEN)
                .ok_or(ProtocolError::InvalidMetadataEntryLength)?;
            let header = block
                .get(position..header_end)
                .ok_or(ProtocolError::InvalidMetadataEntryLength)?;
            let tagged_id = u16::from_be_bytes(header[0..2].try_into().unwrap());
            let critical = tagged_id & CRITICAL_BIT != 0;
            let key_id = tagged_id & KEY_MASK;
            let name_len = usize::from(header[2]);
            let value_len_u32 = u32::from_be_bytes(header[3..7].try_into().unwrap());
            let value_len = usize::try_from(value_len_u32)
                .map_err(|_| ProtocolError::InvalidMetadataEntryLength)?;
            let name_start = header_end;
            let value_start = name_start
                .checked_add(name_len)
                .ok_or(ProtocolError::InvalidMetadataEntryLength)?;
            let entry_end = value_start
                .checked_add(value_len)
                .ok_or(ProtocolError::InvalidMetadataEntryLength)?;
            let name_bytes = block
                .get(name_start..value_start)
                .ok_or(ProtocolError::InvalidMetadataEntryLength)?;
            let value = block
                .get(value_start..entry_end)
                .ok_or(ProtocolError::InvalidMetadataEntryLength)?;
            if value_len_u32 > limits.max_value_bytes {
                return Err(ProtocolError::MetadataValueTooLarge(value_len_u32));
            }

            if let Some((previous_id, previous_name)) = &previous_order {
                let order = previous_id
                    .cmp(&key_id)
                    .then_with(|| previous_name.as_slice().cmp(name_bytes));
                if order.is_ge() {
                    return Err(if order.is_eq() {
                        ProtocolError::DuplicateMetadataKey
                    } else {
                        ProtocolError::NonCanonicalMetadataOrder
                    });
                }
            }
            previous_order = Some((key_id, name_bytes.to_vec()));

            let key = if key_id == USER_KEY_ID {
                let name = std::str::from_utf8(name_bytes)
                    .map_err(|_| ProtocolError::InvalidUserMetadataKey)?;
                validate_user_key(name)?;
                Some(MetadataKey::User(name.to_owned()))
            } else {
                if !name_bytes.is_empty() {
                    return Err(ProtocolError::InvalidUserMetadataKey);
                }
                match ReservedMetadataKey::try_from(key_id) {
                    Ok(key) => Some(MetadataKey::Reserved(key)),
                    Err(()) if critical => {
                        return Err(ProtocolError::UnknownCriticalMetadataKey(key_id));
                    }
                    Err(()) => None,
                }
            };

            if let Some(key) = key {
                metadata.insert(key, value.to_vec())?;
            }
            position = entry_end;
        }

        Ok((metadata, &input[4 + block_len..]))
    }
}

fn validate_user_key(name: &str) -> Result<(), ProtocolError> {
    if name.is_empty()
        || name.len() > u8::MAX as usize
        || !name.bytes().all(|byte| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || b"-_.".contains(&byte)
        })
    {
        return Err(ProtocolError::InvalidUserMetadataKey);
    }
    Ok(())
}
