package ru.moonlightproject.bridge.otel;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.concurrent.TimeUnit;
import ru.moonlightproject.bridge.client.MoonLightTelemetry;

public final class OpenTelemetryFixtureMain {
    private OpenTelemetryFixtureMain() { }

    public static void main(String[] args) {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        var telemetry = new MoonLightOpenTelemetry(sdk);
        var observation = telemetry.startRequest(new MoonLightTelemetry.RequestInfo(
            0x12, -1L, 42, "MonitoringService/Ping"));
        check(observation.traceContext() != null);
        observation.finish(84, null);
        provider.forceFlush().join(1_000, TimeUnit.MILLISECONDS);

        var spans = exporter.getFinishedSpanItems();
        check(spans.size() == 1);
        check(spans.getFirst().getName().equals("MonitoringService/Ping"));
        check(spans.getFirst().getKind() == io.opentelemetry.api.trace.SpanKind.CLIENT);
        check(spans.getFirst().getAttributes().asMap().entrySet().stream().anyMatch(entry ->
            entry.getKey().getKey().equals("rpc.request_id")
                && entry.getValue().equals("18446744073709551615")));
        check("moonlight-observability-java".equals(MoonLightOtlp
            .serviceResource("moonlight-observability-java")
            .getAttribute(AttributeKey.stringKey("service.name"))));
        checkStagesKeepTheirOffsets(telemetry, exporter, provider);
        provider.close();
    }

    private static void checkStagesKeepTheirOffsets(
        MoonLightOpenTelemetry telemetry, InMemorySpanExporter exporter, SdkTracerProvider provider
    ) {
        exporter.reset();
        long start = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(10);
        var observation = telemetry.startRequest(new MoonLightTelemetry.RequestInfo(
            0x12, 7, 42, "MonitoringService/Ping", start));
        long ms = TimeUnit.MILLISECONDS.toNanos(1);
        observation.recordStage("protobuf.encode.MonitoringService.Ping", start, start + ms);
        observation.recordStage(MoonLightTelemetry.STAGE_WAIT, start + 2 * ms, start + 6 * ms);
        observation.finish(84, null);
        provider.forceFlush().join(1_000, TimeUnit.MILLISECONDS);

        var spans = exporter.getFinishedSpanItems();
        check(spans.size() == 3);
        var root = spans.stream().filter(s -> s.getName().equals("MonitoringService/Ping")).findFirst().orElseThrow();
        var encode = spans.stream().filter(s -> s.getName().startsWith("protobuf.encode.")).findFirst().orElseThrow();
        var wait = spans.stream().filter(s -> s.getName().equals(MoonLightTelemetry.STAGE_WAIT)).findFirst().orElseThrow();
        check(encode.getParentSpanId().equals(root.getSpanId()));
        check(wait.getParentSpanId().equals(root.getSpanId()));
        check(encode.getStartEpochNanos() == root.getStartEpochNanos());
        check(wait.getStartEpochNanos() - root.getStartEpochNanos() == 2 * ms);
        check(wait.getEndEpochNanos() - wait.getStartEpochNanos() == 4 * ms);
        check(root.getEndEpochNanos() >= wait.getEndEpochNanos());
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("OpenTelemetry adapter fixture failed");
    }
}
