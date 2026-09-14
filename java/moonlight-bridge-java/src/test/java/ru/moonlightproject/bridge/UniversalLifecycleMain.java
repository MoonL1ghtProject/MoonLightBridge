package ru.moonlightproject.bridge;

import java.util.concurrent.CompletionException;

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
        System.out.println("MoonLightBridge universal lifecycle tests passed");
    }
}
