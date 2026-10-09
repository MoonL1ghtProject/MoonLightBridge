/**
 * Application-owned OpenTelemetry and bounded OTLP/HTTP integration for MoonLightBridge clients.
 *
 * <p>Generated Java spans propagate their context to Rust and use readable
 * {@code Service/Method} names. Adding this module alone sends nothing: applications explicitly
 * construct an adapter, select an endpoint and service name, and own its lifecycle.</p>
 */
package ru.moonlightproject.bridge.otel;
