package ru.moonlightproject.bridge.client;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.List;

public interface MoonLightChannel extends AutoCloseable {
    CompletableFuture<byte[]> request(int methodId, byte[] body, Duration deadline);
    CompletableFuture<Void> ping(Duration timeout);

    default CompletableFuture<List<byte[]>> requestBatch(List<MoonLightRequest> requests) {
        List<CompletableFuture<byte[]>> futures = requests.stream()
            .map(request -> request(request.methodId(), request.body(), request.deadline()))
            .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }
}
