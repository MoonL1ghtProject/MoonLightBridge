package ru.moonlightproject.bridge.client;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.function.Consumer;

public interface MoonLightChannel extends AutoCloseable {
    CompletableFuture<byte[]> request(int methodId, byte[] body, Duration deadline);
    CompletableFuture<Void> ping(Duration timeout);

    default CompletableFuture<MoonLightHealth> health(Duration timeout) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("health is not supported"));
    }

    default AutoCloseable subscribe(int eventId, Consumer<byte[]> listener) {
        throw new UnsupportedOperationException("server events are not supported");
    }

    default CompletableFuture<List<byte[]>> requestBatch(List<MoonLightRequest> requests) {
        List<CompletableFuture<byte[]>> futures = requests.stream()
            .map(request -> request(request.methodId(), request.body(), request.deadline()))
            .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }
}
