# moonlight-bridge-protocol

Wire-format primitives shared by the MoonLightBridge Rust server and Java client.
The crate contains frame kinds, capability flags, structured error codes, size limits
and strict frame encoding/decoding without an async-runtime dependency.

Most applications should depend on `moonlight-bridge-server`, which re-exports the
public error codes and supplies the Tokio runtime.

See the [MoonLightBridge repository](https://github.com/MoonL1ghtProject/MoonLightBridge)
for Java integration, generated APIs, protocol documentation and complete examples.

Licensed under MIT OR Apache-2.0.
