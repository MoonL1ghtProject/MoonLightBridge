use moonlight_bridge_protocol::{
    ErrorCode, Frame, FrameKind, HEADER_LEN, Metadata, MetadataKey, MetadataLimits, PeerSettingsV2,
    ProtocolError, ReservedMetadataKey,
};

const SHARED_METADATA_VECTOR: &str =
    include_str!("../../../testdata/protocol-v2/metadata-deadline-region.hex");

#[test]
fn frame_header_uses_wire_version_two_without_changing_layout() {
    let encoded = Frame::new(FrameKind::Request, 0x1020_3040, 7, b"body".to_vec())
        .encode()
        .unwrap();

    assert_eq!(encoded.len(), HEADER_LEN + 4);
    assert_eq!(encoded[4], 2);
    assert_eq!(&encoded[12..16], &0x1020_3040_u32.to_be_bytes());
    assert_eq!(&encoded[16..24], &7_u64.to_be_bytes());
}

#[test]
fn metadata_encodes_reserved_keys_before_user_keys_in_canonical_order() {
    let mut metadata = Metadata::new();
    metadata.insert_user("x-region", b"eu".to_vec()).unwrap();
    metadata
        .insert_reserved(
            ReservedMetadataKey::DeadlineMillis,
            100_u32.to_be_bytes().to_vec(),
        )
        .unwrap();

    let mut encoded = Vec::new();
    metadata.encode_prefix(&mut encoded).unwrap();

    let expected = SHARED_METADATA_VECTOR.trim();
    let expected: Vec<u8> = (0..expected.len())
        .step_by(2)
        .map(|index| u8::from_str_radix(&expected[index..index + 2], 16).unwrap())
        .collect();
    assert_eq!(encoded, expected);

    let payload = b"payload";
    encoded.extend_from_slice(payload);
    let (decoded, remainder) = Metadata::decode(&encoded, MetadataLimits::default()).unwrap();
    assert_eq!(remainder, payload);
    assert_eq!(
        decoded.get(&MetadataKey::Reserved(ReservedMetadataKey::DeadlineMillis)),
        Some(100_u32.to_be_bytes().as_slice())
    );
    assert_eq!(
        decoded.get(&MetadataKey::User("x-region".into())),
        Some(b"eu".as_slice())
    );
}

#[test]
fn metadata_rejects_duplicate_singleton_keys() {
    let encoded = [
        0, 0, 0, 18, // block length
        0x80, 1, 0, 0, 0, 0, 2, 0, 1, // deadline
        0x80, 1, 0, 0, 0, 0, 2, 0, 2, // duplicate deadline
    ];

    assert_eq!(
        Metadata::decode(&encoded, MetadataLimits::default()),
        Err(ProtocolError::DuplicateMetadataKey)
    );
}

#[test]
fn metadata_rejects_unknown_critical_reserved_key() {
    let encoded = [
        0, 0, 0, 7, // block length
        0x80, 0x40, 0, 0, 0, 0, 0, // unknown critical key 64
    ];

    assert_eq!(
        Metadata::decode(&encoded, MetadataLimits::default()),
        Err(ProtocolError::UnknownCriticalMetadataKey(64))
    );
}

#[test]
fn metadata_checks_canonical_order_across_ignored_optional_keys() {
    let encoded = [
        0, 0, 0, 14, // block length
        0, 64, 0, 0, 0, 0, 0, // unknown optional key 64
        0x80, 1, 0, 0, 0, 0, 0, // known key 1 is out of order
    ];

    assert_eq!(
        Metadata::decode(&encoded, MetadataLimits::default()),
        Err(ProtocolError::NonCanonicalMetadataOrder)
    );
}

#[test]
fn metadata_enforces_count_and_encoded_byte_limits() {
    let two_empty_entries = [
        0, 0, 0, 14, // block length
        0x80, 1, 0, 0, 0, 0, 0, // deadline
        0x80, 2, 0, 0, 0, 0, 0, // trace context
    ];
    let one_entry = MetadataLimits {
        max_bytes: 64,
        max_entries: 1,
        max_value_bytes: 32,
    };
    assert_eq!(
        Metadata::decode(&two_empty_entries, one_entry),
        Err(ProtocolError::TooManyMetadataEntries(2))
    );

    let eight_bytes = MetadataLimits {
        max_bytes: 8,
        max_entries: 8,
        max_value_bytes: 32,
    };
    assert_eq!(
        Metadata::decode(&two_empty_entries, eight_bytes),
        Err(ProtocolError::MetadataTooLarge(14))
    );
}

#[test]
fn metadata_rejects_overflowing_announced_lengths_before_slicing() {
    let block_overflow = [0xff, 0xff, 0xff, 0xff];
    assert_eq!(
        Metadata::decode(&block_overflow, MetadataLimits::default()),
        Err(ProtocolError::InvalidMetadataLength {
            announced: u32::MAX,
            available: 0,
        })
    );

    let value_overflow = [
        0, 0, 0, 7, // block length
        0x80, 1, 0, 0xff, 0xff, 0xff, 0xff,
    ];
    assert_eq!(
        Metadata::decode(&value_overflow, MetadataLimits::default()),
        Err(ProtocolError::InvalidMetadataEntryLength)
    );
}

#[test]
fn peer_settings_v2_round_trip_all_negotiated_limits() {
    let settings = PeerSettingsV2 {
        max_body_len: 8 * 1024 * 1024,
        max_decoded_body_len: 16 * 1024 * 1024,
        max_metadata_len: 16 * 1024,
        max_in_flight: 256,
        max_concurrent_streams: 64,
        initial_stream_credit: 32,
        compression_codecs: 0b11,
        features: 0x1234,
        diagnostic_features: 0x20,
    };

    assert_eq!(
        PeerSettingsV2::decode(&settings.encode()).unwrap(),
        settings
    );
}

#[test]
fn every_v2_error_code_has_a_stable_wire_value() {
    let cases = [
        (ErrorCode::UnknownMethod, 1),
        (ErrorCode::InvalidRequest, 2),
        (ErrorCode::DeadlineExceeded, 3),
        (ErrorCode::Cancelled, 4),
        (ErrorCode::ResourceExhausted, 5),
        (ErrorCode::Internal, 6),
        (ErrorCode::Unauthenticated, 7),
        (ErrorCode::PermissionDenied, 8),
        (ErrorCode::Unavailable, 9),
        (ErrorCode::CompressionFailure, 10),
        (ErrorCode::ReplayGap, 11),
        (ErrorCode::FailedPrecondition, 12),
        (ErrorCode::UnsupportedProtocol, 13),
    ];

    for (code, wire) in cases {
        assert_eq!(code as u16, wire);
        assert_eq!(ErrorCode::try_from(wire).unwrap(), code);
    }
    assert_eq!(
        ErrorCode::try_from(14),
        Err(ProtocolError::UnknownErrorCode(14))
    );
}

#[test]
fn goaway_has_a_stable_wire_kind() {
    assert_eq!(FrameKind::GoAway as u8, 28);
    assert_eq!(FrameKind::try_from(28).unwrap(), FrameKind::GoAway);
}
