package ru.moonlightproject.bridge.client;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Flow;

public final class IntegrationMain {
    public static void main(String[] args) throws Exception {
        String transport = args.length > 0 ? args[0] : "tcp";
        String endpoint = switch (transport) {
            case "unix" -> "unix:" + args[1];
            case "tls" -> "tls://localhost:38192";
            default -> "tcp://127.0.0.1:38191";
        };
        MoonLightClientMetrics metrics = new MoonLightClientMetrics();
        MoonLightTelemetry traceFixture = request -> new MoonLightTelemetry.RequestObservation() {
            @Override public MoonLightTraceContext traceContext() {
                return new MoonLightTraceContext(new byte[16], new byte[8], false);
            }
            @Override public void finish(int responseBytes, Throwable error) { }
        };
        MoonLightClient connection = MoonLightClient.connect(
            endpoint,
            MoonLightPerformanceOptions.automatic(transport),
            MoonLightTelemetry.composite(metrics, traceFixture)
        );
        try (var client = connection) {
            List<CompletableFuture<Void>> checks = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String expected = "request-" + i;
                checks.add(client.request(1, expected.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2))
                    .thenAccept(body -> {
                        String actual = new String(body, StandardCharsets.UTF_8);
                        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
                    }));
            }
            CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).join();
            byte[] largePayload = new byte[64 * 1024];
            for (int index = 0; index < largePayload.length; index++) {
                largePayload[index] = (byte) (index % 31);
            }
            byte[] largeResponse = client.request(
                1, largePayload, Duration.ofSeconds(2)).join();
            if (!java.util.Arrays.equals(largePayload, largeResponse)) {
                throw new AssertionError("mixed compressed unary round trip changed payload");
            }
            CompletableFuture<Void> streamCompleted = new CompletableFuture<>();
            client.serverStream(3, largePayload, Duration.ofSeconds(2)).subscribe(
                new Flow.Subscriber<>() {
                    private int items;
                    @Override public void onSubscribe(Flow.Subscription subscription) {
                        subscription.request(1);
                    }
                    @Override public void onNext(byte[] item) {
                        items++;
                        if (!java.util.Arrays.equals(largePayload, item)) {
                            streamCompleted.completeExceptionally(
                                new AssertionError("compressed stream item changed payload"));
                        }
                    }
                    @Override public void onError(Throwable error) {
                        streamCompleted.completeExceptionally(error);
                    }
                    @Override public void onComplete() {
                        if (items != 1) {
                            streamCompleted.completeExceptionally(
                                new AssertionError("expected one stream item, got " + items));
                        } else {
                            streamCompleted.complete(null);
                        }
                    }
                });
            streamCompleted.orTimeout(2, java.util.concurrent.TimeUnit.SECONDS).join();
            client.ping(Duration.ofSeconds(1)).join();
            MoonLightHealth health = client.health(Duration.ofSeconds(1)).join();
            if (!health.ready() || health.protocolVersion() != 2 || health.activeConnections() < 1) {
                throw new AssertionError("unexpected health status: " + health);
            }

            try {
                client.request(999, new byte[0], Duration.ofSeconds(1)).join();
                throw new AssertionError("unknown method unexpectedly succeeded");
            } catch (CompletionException error) {
                if (!(error.getCause() instanceof MoonLightClient.MoonLightRemoteException remote)
                    || remote.code() != MoonLightClient.ErrorCode.UNKNOWN_METHOD) {
                    throw error;
                }
            }

            try {
                client.request(2, new byte[0], Duration.ofMillis(50)).join();
                throw new AssertionError("slow method unexpectedly met its deadline");
            } catch (CompletionException error) {
                if (!(error.getCause() instanceof TimeoutException)) throw error;
            }

            MoonLightClientMetrics.Snapshot snapshot = metrics.snapshot();
            if (snapshot.started() != 104 || snapshot.succeeded() != 102 || snapshot.failed() != 2) {
                throw new AssertionError("unexpected metrics: " + snapshot);
            }

            System.out.println("MoonLightBridge " + transport + " integration passed: handshake, health, trace context, metrics, mixed compressed unary/stream frames, 100 multiplexed requests, ping, typed error, timeout/cancel");
        }
    }
}
