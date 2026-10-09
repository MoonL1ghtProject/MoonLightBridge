package ru.moonlightproject.bridge.otel;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import java.util.Objects;

/** Caller-owned bounded OTLP/HTTP lifecycle. */
public final class MoonLightOtlp implements AutoCloseable {
    private final SdkTracerProvider provider;
    private final MoonLightOpenTelemetry telemetry;

    private MoonLightOtlp(SdkTracerProvider provider, OpenTelemetrySdk sdk) {
        this.provider = provider;
        telemetry = new MoonLightOpenTelemetry(sdk);
    }

    /**
     * Creates a bounded batch exporter. No network listener is started.
     *
     * @param endpoint OTLP HTTP traces endpoint
     * @param queueCapacity maximum queued spans
     * @return lifecycle and MoonLight telemetry adapter
     */
    public static MoonLightOtlp create(String endpoint, int queueCapacity) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        var exporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build();
        var processor = BatchSpanProcessor.builder(exporter)
            .setMaxQueueSize(queueCapacity)
            .setMaxExportBatchSize(Math.min(queueCapacity, 512))
            .setScheduleDelay(Duration.ofSeconds(1))
            .build();
        var provider = SdkTracerProvider.builder().addSpanProcessor(processor).build();
        var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        return new MoonLightOtlp(provider, sdk);
    }

    /** Returns the telemetry implementation passed to MoonLight clients. */
    public MoonLightOpenTelemetry telemetry() { return telemetry; }

    @Override public void close() { provider.close(); }
}
