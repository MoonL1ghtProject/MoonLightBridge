package ru.moonlightproject.bridge.client;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class BenchmarkMain {
    private static final byte[] PAYLOAD = new byte[32];

    public static void main(String[] args) throws Exception {
        String endpoint = args[0];
        try (var client = MoonLightClient.connect(endpoint)) {
            for (int i = 0; i < 1_000; i++) client.request(1, PAYLOAD, Duration.ofSeconds(2)).join();

            long[] latency = new long[2_000];
            for (int i = 0; i < latency.length; i++) {
                long started = System.nanoTime();
                client.request(1, PAYLOAD, Duration.ofSeconds(2)).join();
                latency[i] = System.nanoTime() - started;
            }
            Arrays.sort(latency);

            int rounds = 100;
            int batchSize = 256;
            List<MoonLightRequest> batch = java.util.stream.IntStream.range(0, batchSize)
                .mapToObj(ignored -> new MoonLightRequest(1, PAYLOAD, Duration.ofSeconds(5)))
                .toList();
            long started = System.nanoTime();
            for (int round = 0; round < rounds; round++) client.requestBatch(batch).join();
            long elapsed = System.nanoTime() - started;
            double requestsPerSecond = rounds * batchSize / (elapsed / 1_000_000_000.0);

            System.out.printf(
                "MoonLightBridge benchmark %s: latency p50=%.1fµs p95=%.1fµs p99=%.1fµs, pipelined throughput=%.0f req/s%n",
                endpoint,
                micros(percentile(latency, 50)),
                micros(percentile(latency, 95)),
                micros(percentile(latency, 99)),
                requestsPerSecond
            );
        }
    }

    private static long percentile(long[] sorted, int percentile) {
        return sorted[Math.min(sorted.length - 1, (sorted.length * percentile) / 100)];
    }

    private static double micros(long nanos) { return nanos / 1_000.0; }
}
