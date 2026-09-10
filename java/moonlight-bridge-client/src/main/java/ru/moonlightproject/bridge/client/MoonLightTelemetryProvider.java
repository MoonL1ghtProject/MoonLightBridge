package ru.moonlightproject.bridge.client;

/** Service-provider hook used by optional, bundled MoonLightBridge observability modules. */
public interface MoonLightTelemetryProvider {
    MoonLightTelemetry createTelemetry();
}
