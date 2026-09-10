package ru.moonlightproject.bridge.client;

import java.util.concurrent.CompletionException;

public final class NonBlockingStartMain {
    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        var client = ReconnectingMoonLightClient.start("tcp://127.0.0.1:9");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        if (elapsedMillis > 250) {
            throw new AssertionError("non-blocking start took " + elapsedMillis + " ms");
        }
        if (client.firstConnection().toCompletableFuture().isDone()) {
            throw new AssertionError("first connection completed while backend was unavailable");
        }
        client.close();
        try {
            client.firstConnection().toCompletableFuture().join();
            throw new AssertionError("closing before connection should fail readiness");
        } catch (CompletionException error) {
            if (!(error.getCause() instanceof ReconnectingMoonLightClient.BackendUnavailableException)) {
                throw error;
            }
        }
        System.out.println("MoonLightBridge non-blocking start test passed in " + elapsedMillis + " ms");
    }
}
