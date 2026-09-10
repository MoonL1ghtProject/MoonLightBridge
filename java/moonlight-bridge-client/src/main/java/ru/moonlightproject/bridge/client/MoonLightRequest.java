package ru.moonlightproject.bridge.client;

import java.time.Duration;
import java.util.Objects;

public record MoonLightRequest(int methodId, byte[] body, Duration deadline) {
    public MoonLightRequest {
        Objects.requireNonNull(body);
        Objects.requireNonNull(deadline);
    }
}
