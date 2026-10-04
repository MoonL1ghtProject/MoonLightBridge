package ru.moonlightproject.bridge.client;

import java.time.Duration;
import java.util.Objects;

/**
 * One raw request used by {@link MoonLightChannel#requestBatch}.
 *
 * @param methodId stable generated method identifier
 * @param body encoded request payload
 * @param deadline maximum time allowed for this request
 * @param policy generated RPC policy, or {@code null} for a raw call
 */
public record MoonLightRequest(int methodId, byte[] body, Duration deadline, RpcPolicy policy) {
    /**
     * Creates a raw request without a generated policy.
     *
     * @param methodId stable generated method identifier
     * @param body encoded request payload
     * @param deadline maximum time allowed for this request
     */
    public MoonLightRequest(int methodId, byte[] body, Duration deadline) {
        this(methodId, body, deadline, null);
    }

    /** Validates required request values. */
    public MoonLightRequest {
        Objects.requireNonNull(body);
        Objects.requireNonNull(deadline);
    }
}
