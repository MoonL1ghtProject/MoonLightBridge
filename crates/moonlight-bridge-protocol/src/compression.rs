use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};

/// Compression codec negotiated for one frame payload.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum CompressionCodec {
    /// Payload bytes are transmitted unchanged.
    None = 0,
    /// Zstandard frame compression.
    Zstd = 1,
}

impl TryFrom<u8> for CompressionCodec {
    type Error = CompressionError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::None),
            1 => Ok(Self::Zstd),
            other => Err(CompressionError::UnknownCodec(other)),
        }
    }
}

/// Bounded compression settings applied after protocol negotiation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CompressionPolicy {
    /// Smallest payload considered for compression.
    pub min_payload_bytes: usize,
    /// Minimum byte savings required to keep compressed output.
    pub min_savings_bytes: usize,
    /// Largest permitted decoded payload.
    pub max_decoded_body_len: u32,
    /// Largest permitted decoded-to-encoded byte ratio.
    pub max_expansion_ratio: u32,
    /// Zstandard compression level.
    pub compression_level: i32,
}

impl Default for CompressionPolicy {
    fn default() -> Self {
        Self {
            min_payload_bytes: 1024,
            min_savings_bytes: 64,
            max_decoded_body_len: 16 * 1024 * 1024,
            max_expansion_ratio: 64,
            compression_level: 1,
        }
    }
}

impl CompressionPolicy {
    /// Compresses an owned payload when the negotiated codec, threshold, and savings permit it.
    pub fn encode(
        self,
        payload: Vec<u8>,
        codec: CompressionCodec,
    ) -> Result<CompressedPayload, CompressionError> {
        self.validate()?;
        let original_len =
            u32::try_from(payload.len()).map_err(|_| CompressionError::DecodedLengthTooLarge {
                announced: u32::MAX,
                maximum: self.max_decoded_body_len,
            })?;
        if original_len > self.max_decoded_body_len {
            return Err(CompressionError::DecodedLengthTooLarge {
                announced: original_len,
                maximum: self.max_decoded_body_len,
            });
        }
        if codec == CompressionCodec::None || payload.len() < self.min_payload_bytes {
            return Ok(CompressedPayload {
                codec: CompressionCodec::None,
                original_len,
                bytes: payload,
            });
        }

        let compressed = zstd::bulk::compress(&payload, self.compression_level)
            .map_err(|_| CompressionError::CodecFailure)?;
        let worthwhile = compressed
            .len()
            .checked_add(self.min_savings_bytes)
            .is_some_and(|minimum_original| minimum_original <= payload.len());
        let within_ratio = (payload.len() as u128)
            <= (compressed.len() as u128) * (self.max_expansion_ratio as u128);
        if worthwhile && within_ratio {
            Ok(CompressedPayload {
                codec,
                original_len,
                bytes: compressed,
            })
        } else {
            Ok(CompressedPayload {
                codec: CompressionCodec::None,
                original_len,
                bytes: payload,
            })
        }
    }

    /// Decodes one payload after enforcing decoded size, ratio, and shared byte-budget limits.
    pub fn decode(
        self,
        codec: CompressionCodec,
        encoded: &[u8],
        original_len: u32,
        budget: &DecodedByteBudget,
    ) -> Result<Vec<u8>, CompressionError> {
        self.decode_with_reservation(codec, encoded, original_len, budget)
            .map(|(bytes, _reservation)| bytes)
    }

    /// Decodes a payload and retains its shared-budget reservation for the caller's lifetime.
    pub fn decode_with_reservation(
        self,
        codec: CompressionCodec,
        encoded: &[u8],
        original_len: u32,
        budget: &DecodedByteBudget,
    ) -> Result<(Vec<u8>, Option<DecodedBytePermit>), CompressionError> {
        self.validate()?;
        if original_len > self.max_decoded_body_len {
            return Err(CompressionError::DecodedLengthTooLarge {
                announced: original_len,
                maximum: self.max_decoded_body_len,
            });
        }
        if codec == CompressionCodec::None {
            if encoded.len() != original_len as usize {
                return Err(CompressionError::DecodedLengthMismatch {
                    announced: original_len,
                    actual: encoded.len(),
                });
            }
            return Ok((encoded.to_vec(), None));
        }

        let maximum_from_ratio = (encoded.len() as u128) * (self.max_expansion_ratio as u128);
        if (original_len as u128) > maximum_from_ratio {
            return Err(CompressionError::ExpansionRatioExceeded {
                compressed: encoded.len(),
                decoded: original_len,
                maximum_ratio: self.max_expansion_ratio,
            });
        }
        let reservation = budget.try_reserve(original_len as usize)?;
        let decoded = zstd::bulk::decompress(encoded, original_len as usize)
            .map_err(|_| CompressionError::CorruptPayload)?;
        if decoded.len() != original_len as usize {
            return Err(CompressionError::DecodedLengthMismatch {
                announced: original_len,
                actual: decoded.len(),
            });
        }
        Ok((decoded, Some(reservation)))
    }

    fn validate(self) -> Result<(), CompressionError> {
        if self.max_decoded_body_len == 0 || self.max_expansion_ratio == 0 {
            return Err(CompressionError::InvalidPolicy);
        }
        Ok(())
    }
}

/// Result of applying a [`CompressionPolicy`] to an owned payload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompressedPayload {
    /// Codec actually used; [`CompressionCodec::None`] means the original allocation was retained.
    pub codec: CompressionCodec,
    /// Original decoded byte length.
    pub original_len: u32,
    /// Encoded payload bytes.
    pub bytes: Vec<u8>,
}

/// Process-shared decoded-byte admission budget.
#[derive(Debug, Clone)]
pub struct DecodedByteBudget {
    inner: Arc<BudgetInner>,
}

#[derive(Debug)]
struct BudgetInner {
    capacity: usize,
    used: AtomicUsize,
}

impl DecodedByteBudget {
    /// Creates a positive decoded-byte budget.
    pub fn new(capacity: usize) -> Result<Self, CompressionError> {
        if capacity == 0 {
            return Err(CompressionError::InvalidBudget);
        }
        Ok(Self {
            inner: Arc::new(BudgetInner {
                capacity,
                used: AtomicUsize::new(0),
            }),
        })
    }

    /// Attempts to reserve bytes until the returned permit is dropped.
    pub fn try_reserve(&self, requested: usize) -> Result<DecodedBytePermit, CompressionError> {
        let mut used = self.inner.used.load(Ordering::Relaxed);
        loop {
            let available = self.inner.capacity.saturating_sub(used);
            if requested > available {
                return Err(CompressionError::DecodedBudgetExhausted {
                    requested,
                    available,
                });
            }
            let Some(updated) = used.checked_add(requested) else {
                return Err(CompressionError::DecodedBudgetExhausted {
                    requested,
                    available,
                });
            };
            match self.inner.used.compare_exchange_weak(
                used,
                updated,
                Ordering::AcqRel,
                Ordering::Relaxed,
            ) {
                Ok(_) => {
                    return Ok(DecodedBytePermit {
                        inner: self.inner.clone(),
                        reserved: requested,
                    });
                }
                Err(observed) => used = observed,
            }
        }
    }
}

/// RAII reservation from a [`DecodedByteBudget`].
#[derive(Debug)]
pub struct DecodedBytePermit {
    inner: Arc<BudgetInner>,
    reserved: usize,
}

impl Drop for DecodedBytePermit {
    fn drop(&mut self) {
        self.inner.used.fetch_sub(self.reserved, Ordering::Release);
    }
}

/// Failure produced by bounded compression or decompression.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CompressionError {
    /// Compression settings contain a zero safety bound.
    InvalidPolicy,
    /// A decoded-byte budget cannot have zero capacity.
    InvalidBudget,
    /// The wire codec identifier is unknown.
    UnknownCodec(u8),
    /// Compression failed before an output frame was produced.
    CodecFailure,
    /// The compressed bytes are not a valid frame for the negotiated codec.
    CorruptPayload,
    /// The announced decoded size exceeds local policy.
    DecodedLengthTooLarge {
        /// Size announced by metadata.
        announced: u32,
        /// Maximum accepted size.
        maximum: u32,
    },
    /// The decoded byte count differs from metadata.
    DecodedLengthMismatch {
        /// Size announced by metadata.
        announced: u32,
        /// Actual decoded or uncompressed size.
        actual: usize,
    },
    /// The announced decoded-to-encoded ratio exceeds policy.
    ExpansionRatioExceeded {
        /// Encoded byte count.
        compressed: usize,
        /// Announced decoded byte count.
        decoded: u32,
        /// Maximum permitted ratio.
        maximum_ratio: u32,
    },
    /// The process-shared decoded-byte budget has insufficient capacity.
    DecodedBudgetExhausted {
        /// Requested reservation.
        requested: usize,
        /// Capacity available at the time of the attempt.
        available: usize,
    },
}

impl std::fmt::Display for CompressionError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{self:?}")
    }
}

impl std::error::Error for CompressionError {}
