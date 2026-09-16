package ru.moonlightproject.bridge;

import java.util.concurrent.CompletionException;
import java.util.Map;

public final class UniversalLifecycleMain {
    private UniversalLifecycleMain() { }

    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        MoonLightBridge bridge = MoonLightBridge.start("tcp://127.0.0.1:1");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        if (elapsedMillis > 1_000) {
            throw new AssertionError("start performed blocking connection work: " + elapsedMillis + " ms");
        }
        if (bridge.channel() != bridge) throw new AssertionError("facade must be its own channel");

        bridge.close();
        if (!bridge.isClosed()) throw new AssertionError("close state was not recorded");
        try {
            bridge.firstConnection().toCompletableFuture().join();
            throw new AssertionError("first connection completed successfully after close");
        } catch (CompletionException expected) {
            // Expected: the unavailable endpoint never completed its first handshake.
        }
        bridge.close();

        try (var group = MoonLightBridgeGroup.start(Map.of(
            "loopback", "tcp://127.0.0.1:1",
            "secondary", "tcp://127.0.0.1:2"
        ))) {
            if (!group.names().equals(java.util.Set.of("loopback", "secondary"))) {
                throw new AssertionError("group did not preserve all backend names");
            }
            if (group.channel("loopback") != group.bridge("loopback")) {
                throw new AssertionError("named channel is not the configured bridge");
            }
            try {
                group.channel("missing");
                throw new AssertionError("unknown backend name was accepted");
            } catch (IllegalArgumentException expected) {
                // Expected: routing is explicit and never falls back to another backend.
            }
        }
        System.out.println("MoonLightBridge universal lifecycle tests passed");
    }
}
