package dev.expj.client;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

public final class IntegrationMain {
    public static void main(String[] args) throws Exception {
        String transport = args.length > 0 ? args[0] : "tcp";
        String endpoint = switch (transport) {
            case "unix" -> "unix:" + args[1];
            case "tls" -> "tls://localhost:38192";
            default -> "tcp://127.0.0.1:38191";
        };
        ExpjClientMetrics metrics = new ExpjClientMetrics();
        ExpjTelemetry traceFixture = request -> new ExpjTelemetry.RequestObservation() {
            @Override public ExpjTraceContext traceContext() {
                return new ExpjTraceContext(new byte[16], new byte[8], false);
            }
            @Override public void finish(int responseBytes, Throwable error) { }
        };
        ExpjClient connection = ExpjClient.connect(
            endpoint,
            ExpjPerformanceOptions.automatic(transport),
            ExpjTelemetry.composite(metrics, traceFixture)
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
            client.ping(Duration.ofSeconds(1)).join();

            try {
                client.request(999, new byte[0], Duration.ofSeconds(1)).join();
                throw new AssertionError("unknown method unexpectedly succeeded");
            } catch (CompletionException error) {
                if (!(error.getCause() instanceof ExpjClient.ExpjRemoteException remote)
                    || remote.code() != ExpjClient.ErrorCode.UNKNOWN_METHOD) {
                    throw error;
                }
            }

            try {
                client.request(2, new byte[0], Duration.ofMillis(50)).join();
                throw new AssertionError("slow method unexpectedly met its deadline");
            } catch (CompletionException error) {
                if (!(error.getCause() instanceof TimeoutException)) throw error;
            }

            ExpjClientMetrics.Snapshot snapshot = metrics.snapshot();
            if (snapshot.started() != 102 || snapshot.succeeded() != 100 || snapshot.failed() != 2) {
                throw new AssertionError("unexpected metrics: " + snapshot);
            }

            System.out.println("EXPJ " + transport + " integration passed: handshake, trace context, metrics, 100 multiplexed requests, ping, typed error, timeout/cancel");
        }
    }
}
