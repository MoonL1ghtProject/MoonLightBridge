package ru.moonlightproject.bridge.sentry;

import io.sentry.Sentry;
import ru.moonlightproject.bridge.client.MoonLightTelemetry;

/** Runtime opt-out must prevent SDK initialization, including the relocated provider. */
public final class TelemetryDisabledMain {
    public static void main(String[] args) {
        System.setProperty("moonlight.bridge.telemetry.enabled", "false");
        if (new FrameworkSentryProvider().createTelemetry() != MoonLightTelemetry.disabled()) {
            throw new AssertionError("runtime opt-out did not return disabled provider");
        }
        if (Sentry.isEnabled()) throw new AssertionError("disabled provider initialized Sentry");
        System.out.println("Telemetry runtime opt-out prevents SDK initialization");
    }
}
