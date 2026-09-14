package ru.moonlightproject.bridge.client;

/** Service-provider hook used by optional, bundled MoonLightBridge observability modules. */
public interface MoonLightTelemetryProvider {
    /** Creates the provider's runtime instrumentation implementation.
     * @return initialized implementation
     */
    MoonLightTelemetry createTelemetry();
}
