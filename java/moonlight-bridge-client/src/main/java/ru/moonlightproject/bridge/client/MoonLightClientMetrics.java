package ru.moonlightproject.bridge.client;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

public final class MoonLightClientMetrics implements MoonLightTelemetry {
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

    public Snapshot snapshot() {
        return new Snapshot(
            started.sum(), succeeded.sum(), failed.sum(), requestBytes.sum(), responseBytes.sum(),
            totalLatencyNanos.sum(), maxLatencyNanos.get()
        );
    }

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
