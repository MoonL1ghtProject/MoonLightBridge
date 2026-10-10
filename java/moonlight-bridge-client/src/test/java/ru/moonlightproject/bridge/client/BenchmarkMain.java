package ru.moonlightproject.bridge.client;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class BenchmarkMain {
    private static final byte[] PAYLOAD = new byte[32];
    private static final int LATENCY_WARMUP_REQUESTS = 5_000;
    private static final int LATENCY_SAMPLES = 5_000;
    private static final int BATCH_SIZE = 128;
    private static final int THROUGHPUT_WARMUP_ROUNDS = 40;
    private static final int THROUGHPUT_ROUNDS = 200;
    private static final int THROUGHPUT_WINDOWS = 5;

    public static void main(String[] args) throws Exception {
        String endpoint = args[0];
        try (var client = MoonLightClient.connect(endpoint)) {
            for (int i = 0; i < LATENCY_WARMUP_REQUESTS; i++) {
                client.request(1, PAYLOAD, Duration.ofSeconds(2)).join();
            }

            long[] latency = new long[LATENCY_SAMPLES];
            for (int i = 0; i < latency.length; i++) {
                long started = System.nanoTime();
                client.request(1, PAYLOAD, Duration.ofSeconds(2)).join();
                latency[i] = System.nanoTime() - started;
            }
            Arrays.sort(latency);

            List<MoonLightRequest> batch = java.util.stream.IntStream.range(0, BATCH_SIZE)
                .mapToObj(ignored -> new MoonLightRequest(1, PAYLOAD, Duration.ofSeconds(5)))
                .toList();
            for (int round = 0; round < THROUGHPUT_WARMUP_ROUNDS; round++) {
                client.requestBatch(batch).join();
            }
            double[] throughput = new double[THROUGHPUT_WINDOWS];
            for (int window = 0; window < throughput.length; window++) {
                long started = System.nanoTime();
                for (int round = 0; round < THROUGHPUT_ROUNDS; round++) {
                    client.requestBatch(batch).join();
                }
                long elapsed = System.nanoTime() - started;
                throughput[window] = THROUGHPUT_ROUNDS * BATCH_SIZE
                    / (elapsed / 1_000_000_000.0);
            }
            Arrays.sort(throughput);

            System.out.printf(
                "MoonLightBridge benchmark %s: latency p50=%.1fµs p95=%.1fµs p99=%.1fµs, pipelined throughput median=%.0f req/s (min=%.0f max=%.0f)%n",
                endpoint,
                micros(percentile(latency, 50)),
                micros(percentile(latency, 95)),
                micros(percentile(latency, 99)),
                throughput[throughput.length / 2],
                throughput[0],
                throughput[throughput.length - 1]
            );
        }
    }

    private static long percentile(long[] sorted, int percentile) {
        return sorted[Math.min(sorted.length - 1, (sorted.length * percentile) / 100)];
    }

    private static double micros(long nanos) { return nanos / 1_000.0; }
}
