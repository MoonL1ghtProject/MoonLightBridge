package ru.moonlightproject.bridge.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ru.moonlightproject.bridge.client.MoonLightTelemetry;

public final class MicrometerFixtureMain {
    private MicrometerFixtureMain() { }

    public static void main(String[] args) {
        var registry = new SimpleMeterRegistry();
        var telemetry = new MoonLightMicrometerTelemetry(registry);
        var observation = telemetry.startRequest(new MoonLightTelemetry.RequestInfo(7, 999, 12));
        observation.finish(24, null);

        check(registry.get("moonlight.bridge.requests.started").counter().count() == 1);
        check(registry.get("moonlight.bridge.requests.succeeded").counter().count() == 1);
        check(registry.get("moonlight.bridge.request.bytes").counter().count() == 12);
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            check(!tag.getKey().equals("request_id"));
            check(!tag.getKey().equals("logical_call_id"));
        }));
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Micrometer adapter fixture failed");
    }
}
