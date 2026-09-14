package ru.moonlightproject.bridge.client;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.function.Consumer;

/** Common asynchronous channel implemented by direct, supervised, and high-level clients. */
public interface MoonLightChannel extends AutoCloseable {
    /** Sends a raw method payload with a relative deadline. */
    CompletableFuture<byte[]> request(int methodId, byte[] body, Duration deadline);
    /** Performs a transport liveness check within {@code timeout}. */
    CompletableFuture<Void> ping(Duration timeout);

    /** Requests the server's built-in health snapshot. */
    default CompletableFuture<MoonLightHealth> health(Duration timeout) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("health is not supported"));
    }

    /** Registers a raw event listener and returns a handle that removes it. */
    default AutoCloseable subscribe(int eventId, Consumer<byte[]> listener) {
        throw new UnsupportedOperationException("server events are not supported");
    }

    /** Submits independent requests together and preserves input order in the result. */
    default CompletableFuture<List<byte[]>> requestBatch(List<MoonLightRequest> requests) {
        List<CompletableFuture<byte[]>> futures = requests.stream()
            .map(request -> request(request.methodId(), request.body(), request.deadline()))
            .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }
}
