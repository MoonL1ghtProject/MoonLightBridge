package dev.expj.client;

import java.time.Duration;
import java.util.Objects;

public record ExpjRequest(int methodId, byte[] body, Duration deadline) {
    public ExpjRequest {
        Objects.requireNonNull(body);
        Objects.requireNonNull(deadline);
    }
}
