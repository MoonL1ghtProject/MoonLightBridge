package ru.moonlightproject.bridge.otel;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
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
        return create(endpoint, queueCapacity, null);
    }

    /** Creates a bounded batch exporter with an explicit OpenTelemetry service name. */
    public static MoonLightOtlp create(String endpoint, int queueCapacity, String serviceName) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        var exporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build();
        var processor = BatchSpanProcessor.builder(exporter)
            .setMaxQueueSize(queueCapacity)
            .setMaxExportBatchSize(Math.min(queueCapacity, 512))
            .setScheduleDelay(Duration.ofSeconds(1))
            .build();
        var providerBuilder = SdkTracerProvider.builder().addSpanProcessor(processor);
        if (serviceName != null) providerBuilder.setResource(serviceResource(serviceName));
        var provider = providerBuilder.build();
        var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        return new MoonLightOtlp(provider, sdk);
    }

    static Resource serviceResource(String serviceName) {
        Objects.requireNonNull(serviceName, "serviceName");
        if (serviceName.isBlank()) throw new IllegalArgumentException("serviceName must not be blank");
        return Resource.getDefault().merge(Resource.create(Attributes.of(
            AttributeKey.stringKey("service.name"), serviceName)));
    }

    /** Returns the telemetry implementation passed to MoonLight clients. */
    public MoonLightOpenTelemetry telemetry() { return telemetry; }

    @Override public void close() { provider.close(); }
}
