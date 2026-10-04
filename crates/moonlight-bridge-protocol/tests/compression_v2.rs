use moonlight_bridge_protocol::{
    CompressionCodec, CompressionError, CompressionPolicy, DecodedByteBudget,
};
use std::hint::black_box;
use std::time::Instant;

fn policy() -> CompressionPolicy {
    CompressionPolicy {
        min_payload_bytes: 32,
        min_savings_bytes: 8,
        max_decoded_body_len: 1024 * 1024,
        max_expansion_ratio: 64,
        compression_level: 1,
    }
}

#[test]
fn zstd_round_trip_preserves_varied_payloads() {
    let policy = policy();
    let budget = DecodedByteBudget::new(2 * 1024 * 1024).unwrap();
    for length in [32, 33, 127, 1024, 16 * 1024] {
        let payload: Vec<u8> = (0..length).map(|index| (index % 7) as u8).collect();
        let encoded = policy
            .encode(payload.clone(), CompressionCodec::Zstd)
            .unwrap();
        let decoded = policy
            .decode(encoded.codec, &encoded.bytes, encoded.original_len, &budget)
            .unwrap();
        assert_eq!(decoded, payload, "round trip failed for {length} bytes");
    }
}

#[test]
fn compression_skips_payloads_below_threshold_or_without_savings() {
    let policy = policy();
    let below = vec![7; policy.min_payload_bytes - 1];
    let encoded = policy
        .encode(below.clone(), CompressionCodec::Zstd)
        .unwrap();
    assert_eq!(encoded.codec, CompressionCodec::None);
    assert_eq!(encoded.bytes, below);

    let incompressible: Vec<u8> = (0..256).map(|value| value as u8).collect();
    let encoded = policy
        .encode(incompressible.clone(), CompressionCodec::Zstd)
        .unwrap();
    assert_eq!(encoded.codec, CompressionCodec::None);
    assert_eq!(encoded.bytes, incompressible);
}

#[test]
fn decompression_rejects_corrupt_frames() {
    let policy = policy();
    let budget = DecodedByteBudget::new(1024).unwrap();
    assert!(matches!(
        policy.decode(CompressionCodec::Zstd, b"not-zstd", 128, &budget),
        Err(CompressionError::CorruptPayload)
    ));
}

#[test]
fn decoded_length_is_checked_before_decompression() {
    let mut policy = policy();
    policy.max_decoded_body_len = 1024;
    let budget = DecodedByteBudget::new(4096).unwrap();
    assert_eq!(
        policy.decode(CompressionCodec::Zstd, &[1], 1025, &budget),
        Err(CompressionError::DecodedLengthTooLarge {
            announced: 1025,
            maximum: 1024,
        })
    );
}

#[test]
fn expansion_ratio_is_checked_without_integer_overflow() {
    let mut policy = policy();
    policy.max_expansion_ratio = 16;
    let budget = DecodedByteBudget::new(4096).unwrap();
    assert_eq!(
        policy.decode(CompressionCodec::Zstd, &[1, 2], 33, &budget),
        Err(CompressionError::ExpansionRatioExceeded {
            compressed: 2,
            decoded: 33,
            maximum_ratio: 16,
        })
    );
}

#[test]
fn shared_decoded_byte_budget_rejects_concurrent_reservations() {
    let policy = policy();
    let budget = DecodedByteBudget::new(128).unwrap();
    let _held = budget.try_reserve(100).unwrap();
    let compressed = policy.encode(vec![0; 64], CompressionCodec::Zstd).unwrap();
    assert_eq!(compressed.codec, CompressionCodec::Zstd);
    assert_eq!(
        policy.decode(
            compressed.codec,
            &compressed.bytes,
            compressed.original_len,
            &budget,
        ),
        Err(CompressionError::DecodedBudgetExhausted {
            requested: 64,
            available: 28,
        })
    );
}

#[test]
#[ignore = "manual release-mode compression benchmark"]
fn benchmark_compression_policy_matrix() {
    const ITERATIONS: usize = 2_000;
    let policy = CompressionPolicy::default();
    let budget = DecodedByteBudget::new(128 * 1024 * 1024).unwrap();
    let below_threshold = vec![b'a'; policy.min_payload_bytes.saturating_sub(1)];
    let repeated_block: Vec<u8> = (0..4096)
        .map(|index| pseudo_random_byte(index as u64))
        .collect();
    let compressible: Vec<u8> = repeated_block
        .iter()
        .copied()
        .cycle()
        .take(64 * 1024)
        .collect();
    let incompressible: Vec<u8> = (0..64 * 1024)
        .map(|index| pseudo_random_byte(index as u64))
        .collect();

    benchmark_case(
        "codec-disabled",
        &policy,
        &budget,
        CompressionCodec::None,
        &compressible,
        ITERATIONS,
    );
    benchmark_case(
        "below-threshold",
        &policy,
        &budget,
        CompressionCodec::Zstd,
        &below_threshold,
        ITERATIONS,
    );
    benchmark_case(
        "compressible-64k",
        &policy,
        &budget,
        CompressionCodec::Zstd,
        &compressible,
        ITERATIONS,
    );
    benchmark_case(
        "incompressible-64k",
        &policy,
        &budget,
        CompressionCodec::Zstd,
        &incompressible,
        ITERATIONS,
    );
}

fn benchmark_case(
    name: &str,
    policy: &CompressionPolicy,
    budget: &DecodedByteBudget,
    codec: CompressionCodec,
    payload: &[u8],
    iterations: usize,
) {
    let started = Instant::now();
    let mut encoded_len = 0;
    let mut selected_codec = CompressionCodec::None;
    for _ in 0..iterations {
        let encoded = policy.encode(black_box(payload.to_vec()), codec).unwrap();
        encoded_len = encoded.bytes.len();
        selected_codec = encoded.codec;
        let decoded = policy
            .decode(
                encoded.codec,
                black_box(&encoded.bytes),
                encoded.original_len,
                budget,
            )
            .unwrap();
        black_box(decoded);
    }
    let nanos_per_round_trip = started.elapsed().as_nanos() / iterations as u128;
    eprintln!(
        "compression-bench case={name} input={} encoded={encoded_len} codec={selected_codec:?} ns/round-trip={nanos_per_round_trip}",
        payload.len(),
    );
}

fn pseudo_random_byte(mut value: u64) -> u8 {
    value = value.wrapping_add(0x9e37_79b9_7f4a_7c15);
    value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    (value ^ (value >> 31)) as u8
}
