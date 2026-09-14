package ru.moonlightproject.bridge.client;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** In-memory client counters suitable for health endpoints and tests. */
public final class MoonLightClientMetrics implements MoonLightTelemetry {
    /** Creates zeroed in-memory counters. */
    public MoonLightClientMetrics() { }
    private final LongAdder started = new LongAdder();
    private final LongAdder succeeded = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder requestBytes = new LongAdder();
    private final LongAdder responseBytes = new LongAdder();
    private final LongAdder totalLatencyNanos = new LongAdder();
    private final LongAccumulator maxLatencyNanos = new LongAccumulator(Long::max, 0);

    @Override
    public RequestObservation startRequest(RequestInfo request) {
        started.increment();
        requestBytes.add(request.requestBytes());
        long start = System.nanoTime();
        return new RequestObservation() {
            @Override public MoonLightTraceContext traceContext() { return null; }

            @Override
            public void finish(int returnedBytes, Throwable error) {
                long elapsed = System.nanoTime() - start;
                totalLatencyNanos.add(elapsed);
                maxLatencyNanos.accumulate(elapsed);
                if (error == null) {
                    succeeded.increment();
                    responseBytes.add(returnedBytes);
                } else {
                    failed.increment();
                }
            }
        };
    }

    /**
     * Returns an immutable point-in-time copy of all counters.
     *
     * @return current cumulative counters
     */
    public Snapshot snapshot() {
        return new Snapshot(
            started.sum(), succeeded.sum(), failed.sum(), requestBytes.sum(), responseBytes.sum(),
            totalLatencyNanos.sum(), maxLatencyNanos.get()
        );
    }

    /**
     * Cumulative request counts, bytes, and latency values.
     *
     * @param started submitted requests
     * @param succeeded successfully completed requests
     * @param failed exceptionally completed requests
     * @param requestBytes encoded request payload bytes
     * @param responseBytes successful response payload bytes
     * @param totalLatencyNanos total observed request latency in nanoseconds
     * @param maxLatencyNanos greatest observed request latency in nanoseconds
     */
    public record Snapshot(
        long started,
        long succeeded,
        long failed,
        long requestBytes,
        long responseBytes,
        long totalLatencyNanos,
        long maxLatencyNanos
    ) { }
}
