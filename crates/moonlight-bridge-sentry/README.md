# moonlight-bridge-sentry (internal)

This crate is an implementation detail of official MoonLightBridge builds. It owns the
framework's Rust telemetry policy and is not a public integration API for plugins or backends.

Do not add it to application dependencies and do not initialize it from plugin code. Public
backend observability hooks live in `moonlight-bridge-server`; framework-owned Sentry routing,
sampling and credentials remain controlled by MoonLightProject builds.

Separate crates.io publication ended after `0.1.0`. The crate remains in the source workspace so
official binaries can be built and tested reproducibly.
