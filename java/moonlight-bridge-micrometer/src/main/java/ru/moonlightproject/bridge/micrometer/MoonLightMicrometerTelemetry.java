package ru.moonlightproject.bridge.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import ru.moonlightproject.bridge.client.MoonLightTelemetry;
import ru.moonlightproject.bridge.client.MoonLightTraceContext;

/** Records bounded, payload-free MoonLightBridge client metrics in a caller-owned registry. */
public final class MoonLightMicrometerTelemetry implements MoonLightTelemetry {
    private final Counter started;
    private final Counter succeeded;
    private final Counter failed;
    private final Counter connectionsOpened;
    private final Counter connectionsClosed;
    private final Counter exporterDrops;
    private final Counter requestBytes;
    private final Counter responseBytes;
    private final Timer latency;

    /**
     * Registers stable aggregate meters. This does not create a server or exporter thread.
     *
     * @param registry application-owned Micrometer registry
     */
    public MoonLightMicrometerTelemetry(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        started = registry.counter("moonlight.bridge.requests.started");
        succeeded = registry.counter("moonlight.bridge.requests.succeeded");
        failed = registry.counter("moonlight.bridge.requests.failed");
        connectionsOpened = registry.counter("moonlight.bridge.connections.opened");
        connectionsClosed = registry.counter("moonlight.bridge.connections.closed");
        exporterDrops = registry.counter("moonlight.bridge.exporter.drops");
        requestBytes = Counter.builder("moonlight.bridge.request.bytes")
            .description("Encoded request payload bytes")
            .baseUnit("bytes")
            .register(registry);
        responseBytes = Counter.builder("moonlight.bridge.response.bytes")
            .description("Encoded successful response payload bytes")
            .baseUnit("bytes")
            .register(registry);
        latency = Timer.builder("moonlight.bridge.request.latency")
            .description("End-to-end client request latency")
            .publishPercentileHistogram()
            .register(registry);
    }

    @Override
    public RequestObservation startRequest(RequestInfo request) {
        started.increment();
        requestBytes.increment(request.requestBytes());
        long startedAt = System.nanoTime();
        return new RequestObservation() {
            private boolean finished;

            @Override public MoonLightTraceContext traceContext() { return null; }

            @Override
            public synchronized void finish(int returnedBytes, Throwable error) {
                if (finished) return;
                finished = true;
                latency.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
                if (error == null) {
                    succeeded.increment();
                    responseBytes.increment(returnedBytes);
                } else {
                    failed.increment();
                }
            }
        };
    }

    @Override public void connectionOpened() { connectionsOpened.increment(); }
    @Override public void connectionClosed(Throwable cause) { connectionsClosed.increment(); }

    /** Records one item discarded by an application-owned bounded exporter queue. */
    public void exporterDropped() { exporterDrops.increment(); }
}
