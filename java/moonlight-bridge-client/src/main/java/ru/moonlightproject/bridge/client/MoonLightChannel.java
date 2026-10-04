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
     * Sends a request under a generated policy, tightening the caller deadline and size limit.
     *
     * @param methodId stable generated method identifier
     * @param body encoded request payload
     * @param deadline caller deadline cap
     * @param policy generated method policy
     * @return future containing the encoded response payload
     */
    default CompletableFuture<byte[]> request(
        int methodId, byte[] body, Duration deadline, RpcPolicy policy
    ) {
        if (body.length > policy.maxRequestBytes()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("request exceeds generated RPC policy"));
        }
        return request(methodId, body, policy.effectiveDeadline(deadline));
    }

    /**
     * Opens a credit-controlled server-streaming RPC.
     *
     * @param methodId stable generated method identifier
     * @param body encoded request payload
     * @param deadline maximum lifetime of the entire stream
     * @return single-subscriber stream whose demand controls wire delivery credit
     */
    default MoonLightServerStream<byte[]> serverStream(int methodId, byte[] body, Duration deadline) {
        throw new UnsupportedOperationException("server streaming is not supported");
    }
    /**
     * Opens a server stream under a generated policy.
     *
     * @param methodId stable generated method identifier
     * @param body encoded request payload
     * @param deadline caller deadline cap
     * @param policy generated method policy
     * @return credit-controlled response stream
     */
    default MoonLightServerStream<byte[]> serverStream(
        int methodId, byte[] body, Duration deadline, RpcPolicy policy
    ) {
        if (body.length > policy.maxRequestBytes()) {
            throw new IllegalArgumentException("request exceeds generated RPC policy");
        }
        return serverStream(methodId, body, policy.effectiveDeadline(deadline));
    }
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
            .map(request -> request.policy() == null
                ? request(request.methodId(), request.body(), request.deadline())
                : request(request.methodId(), request.body(), request.deadline(), request.policy()))
            .toList();
        CompletableFuture<List<byte[]>> result = mapFuture(
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)),
            ignored -> futures.stream().map(CompletableFuture::join).toList());
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) futures.forEach(future -> future.cancel(true));
        });
        return result;
    }
    /**
     * Maps a response while propagating cancellation to its transport future.
     * @param source transport or aggregate future
     * @param mapper response decoder
     * @param <S> source value type
     * @param <T> mapped value type
     * @return cancellable mapped future
     */
    static <S, T> CompletableFuture<T> mapFuture(
        CompletableFuture<S> source, java.util.function.Function<? super S, ? extends T> mapper
    ) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.whenComplete((value, error) -> { if (result.isCancelled()) source.cancel(true); });
        source.whenComplete((value, error) -> {
            if (error != null) result.completeExceptionally(error);
            else if (!result.isDone()) {
                try { result.complete(mapper.apply(value)); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            }
        });
        return result;
    }
}
