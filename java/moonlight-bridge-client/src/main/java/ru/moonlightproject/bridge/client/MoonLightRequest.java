package ru.moonlightproject.bridge.client;

import java.time.Duration;
import java.util.Objects;

/** One raw request used by {@link MoonLightChannel#requestBatch}. */
public record MoonLightRequest(int methodId, byte[] body, Duration deadline) {
    public MoonLightRequest {
        Objects.requireNonNull(body);
        Objects.requireNonNull(deadline);
    }
}
