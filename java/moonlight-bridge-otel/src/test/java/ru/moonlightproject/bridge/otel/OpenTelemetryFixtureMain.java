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
        check(spans.getFirst().getAttributes().asMap().entrySet().stream().anyMatch(entry ->
            entry.getKey().getKey().equals("rpc.request_id")
                && entry.getValue().equals("18446744073709551615")));
        check("moonlight-observability-java".equals(MoonLightOtlp
            .serviceResource("moonlight-observability-java")
            .getAttribute(AttributeKey.stringKey("service.name"))));
        provider.close();
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("OpenTelemetry adapter fixture failed");
    }
}
