# moonlight-bridge-codegen

Schema generator and compatibility checker for
[MoonLightBridge](https://github.com/MoonL1ghtProject/MoonLightBridge).
It reads one Protobuf descriptor set and generates matching Java clients, Rust service
traits, batch helpers and typed server events with identical stable method IDs.

```bash
cargo install moonlight-bridge-codegen --version 0.1.0 --locked
moonlight-bridge-codegen descriptor.pb generated/java
moonlight-bridge-codegen lock descriptor.pb schema.lock --check
```

Rust API crates can call `compile_rust_api` from `build.rs`; the published Gradle plugin
`ru.moonlightproject.bridge` wires the same generation and lock check into Java builds.

See the [code generation guide](https://github.com/MoonL1ghtProject/MoonLightBridge/blob/main/docs/codegen.md)
for the complete setup. Licensed under MIT OR Apache-2.0.
