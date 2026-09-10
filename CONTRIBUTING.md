# Contributing to MoonLightBridge

MoonLightBridge sits on a latency-sensitive boundary between a Minecraft server and a
Rust process. Changes should therefore be small, measurable and covered on both sides of
the protocol whenever applicable.

## Before opening a pull request

1. Describe the failure mode or performance goal before the implementation.
2. Keep wire changes backward compatible and update `proto/schema.lock` intentionally.
3. Never log request payloads, credentials, player chat or other private data.
4. Add a regression test for protocol, lifecycle and concurrency fixes.
5. Run the complete integration suite:

   ```bash
   ./scripts/integration-test.sh
   ```

For Java-only work, `./gradlew check` is sufficient while iterating. For Rust-only work,
use `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets -- -D warnings`
and `cargo test --workspace`.

## Compatibility

The Java bytecode target is 21. Public Java and Rust APIs follow semantic versioning.
Protocol fields and enum values are never reused; removed Protobuf fields must reserve
both their old number and name.

## Performance changes

Include the command, transport, warm-up count, sample count and before/after percentiles.
Avoid claiming improvements from a single cold request. See `docs/performance.md` for the
project benchmark conventions.
