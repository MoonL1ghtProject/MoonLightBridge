package ru.moonlightproject.bridge.client;

import java.time.Duration;
import java.util.Objects;

/**
 * One raw request used by {@link MoonLightChannel#requestBatch}.
 *
 * @param methodId stable generated method identifier
 * @param body encoded request payload
 * @param deadline maximum time allowed for this request
 */
public record MoonLightRequest(int methodId, byte[] body, Duration deadline) {
    /** Validates required request values. */
    public MoonLightRequest {
        Objects.requireNonNull(body);
        Objects.requireNonNull(deadline);
    }
}
