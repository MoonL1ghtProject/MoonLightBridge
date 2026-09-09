package dev.expj.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionException;

public final class ReconnectIntegrationMain {
    public static void main(String[] args) throws Exception {
        Path markerDirectory = Path.of(args[0]);
        try (var client = ReconnectingExpjClient.connect(
            "tcp://127.0.0.1:38191",
            new ReconnectingExpjClient.ReconnectPolicy(Duration.ofMillis(50), Duration.ofMillis(500)))) {
            assertEcho(client, "before-restart");
            Files.createFile(markerDirectory.resolve("ready"));

            await(() -> !client.isConnected(), Duration.ofSeconds(10), "client did not observe disconnect");
            try {
                client.request(1, new byte[0], Duration.ofSeconds(1)).join();
                throw new AssertionError("request while disconnected unexpectedly succeeded");
            } catch (CompletionException error) {
                if (!(error.getCause() instanceof ReconnectingExpjClient.BackendUnavailableException)) throw error;
            }
            Files.createFile(markerDirectory.resolve("disconnected"));

            await(client::isConnected, Duration.ofSeconds(15), "client did not reconnect");
            assertEcho(client, "after-restart");
            System.out.println("EXPJ reconnect integration passed: no replay, fail-fast while down, new request after recovery");
        }
    }

    private static void assertEcho(ReconnectingExpjClient client, String expected) {
        byte[] response = client.request(1, expected.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2)).join();
        String actual = new String(response, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }

    private static void await(Check check, Duration timeout, String message) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!check.get() && System.nanoTime() < deadline) Thread.sleep(25);
        if (!check.get()) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Check { boolean get(); }
}
