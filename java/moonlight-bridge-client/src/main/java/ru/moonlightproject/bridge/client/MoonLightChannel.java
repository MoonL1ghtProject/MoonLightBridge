package ru.moonlightproject.bridge.client;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.function.Consumer;

/** Common asynchronous channel implemented by direct, supervised, and high-level clients. */
public interface MoonLightChannel extends AutoCloseable {
    /**
     * Sends a raw method payload with a relative deadline.
     *
     * @param methodId stable generated method identifier
     * @param body encoded request payload
     * @param deadline maximum time allowed for the call
     * @return future containing the encoded response payload
     */
    CompletableFuture<byte[]> request(int methodId, byte[] body, Duration deadline);
    /**
     * Performs a transport liveness check within {@code timeout}.
     *
     * @param timeout maximum time to wait for PONG
     * @return future completed after the peer returns the matching nonce
     */
    CompletableFuture<Void> ping(Duration timeout);

    /**
     * Requests the server's built-in health snapshot.
     *
     * @param timeout maximum time to wait for the snapshot
     * @return future containing server readiness and load counters
     */
    default CompletableFuture<MoonLightHealth> health(Duration timeout) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("health is not supported"));
    }

    /**
     * Registers a raw event listener and returns a handle that removes it.
     *
     * @param eventId stable generated event identifier
     * @param listener callback receiving encoded event payloads
     * @return closeable subscription handle
     */
    default AutoCloseable subscribe(int eventId, Consumer<byte[]> listener) {
        throw new UnsupportedOperationException("server events are not supported");
    }

    /**
     * Submits independent requests together and preserves input order in the result.
     *
     * @param requests independent requests to enqueue as one caller-side burst
     * @return future containing response bodies in input order
     */
    default CompletableFuture<List<byte[]>> requestBatch(List<MoonLightRequest> requests) {
        List<CompletableFuture<byte[]>> futures = requests.stream()
            .map(request -> request(request.methodId(), request.body(), request.deadline()))
            .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }
}
