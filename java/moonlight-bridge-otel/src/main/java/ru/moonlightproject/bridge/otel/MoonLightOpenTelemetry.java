package ru.moonlightproject.bridge.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.moonlightproject.bridge.client.MoonLightTelemetry;
import ru.moonlightproject.bridge.client.MoonLightTraceContext;

/**
 * Creates client spans through a caller-owned OpenTelemetry SDK.
 *
 * <p>Generated calls use their canonical {@code Service/Method} name. The client span starts
 * before request encoding and ends after response decoding. Each recorded stage becomes a child
 * span at its real offset: {@code protobuf.encode.*}, {@code moonlight.client.prepare},
 * {@code moonlight.client.write},
 * {@code moonlight.client.wait}, {@code moonlight.client.dispatch}, and {@code protobuf.decode.*}.
 * The adapter returns the Java span context to the transport so the Rust request span and its
 * stages continue the same distributed trace. Raw calls fall back to
 * {@code moonlight.rpc.<method-id>}.</p>
 */
public final class MoonLightOpenTelemetry implements MoonLightTelemetry {
    private final io.opentelemetry.api.trace.Tracer tracer;

    /**
     * Creates telemetry without configuring exporters or opening listeners.
     *
     * @param openTelemetry caller-owned OpenTelemetry instance
     */
    public MoonLightOpenTelemetry(OpenTelemetry openTelemetry) {
        tracer = Objects.requireNonNull(openTelemetry, "openTelemetry")
            .getTracer("ru.moonlightproject.bridge", MoonLightOtelVersion.VERSION);
    }

    @Override
    public RequestObservation startRequest(RequestInfo request) {
        String spanName = request.methodName() == null
            ? "moonlight.rpc." + Integer.toUnsignedString(request.methodId(), 16)
            : request.methodName();
        // Map System.nanoTime() marks to wall-clock span timestamps for this request.
        Instant wallNow = Instant.now();
        long epochAnchor = TimeUnit.SECONDS.toNanos(wallNow.getEpochSecond()) + wallNow.getNano();
        long nanoAnchor = System.nanoTime();
        Span span = tracer.spanBuilder(spanName)
            .setSpanKind(SpanKind.CLIENT)
            .setStartTimestamp(epochAnchor + (request.startNanos() - nanoAnchor), TimeUnit.NANOSECONDS)
            .setAttribute("rpc.system", "moonlight_bridge")
            .setAttribute("rpc.method_id", Integer.toUnsignedLong(request.methodId()))
            .setAttribute("rpc.request_id", Long.toUnsignedString(request.requestId()))
            .setAttribute("rpc.request.size", request.requestBytes())
            .startSpan();
        return new RequestObservation() {
            private final AtomicBoolean finished = new AtomicBoolean();

            @Override
            public MoonLightTraceContext traceContext() {
                var context = span.getSpanContext();
                if (!context.isValid()) return null;
                return new MoonLightTraceContext(
                    HexFormat.of().parseHex(context.getTraceId()),
                    HexFormat.of().parseHex(context.getSpanId()),
                    context.isSampled());
            }

            @Override
            public boolean wantsStages() {
                return span.isRecording();
            }

            @Override
            public void recordStage(String name, long startNanos, long endNanos) {
                if (finished.get() || !span.getSpanContext().isSampled()) return;
                tracer.spanBuilder(name)
                    .setParent(Context.root().with(span))
                    .setSpanKind(SpanKind.INTERNAL)
                    .setStartTimestamp(epochAnchor + (startNanos - nanoAnchor), TimeUnit.NANOSECONDS)
                    .startSpan()
                    .end(epochAnchor + (Math.max(startNanos, endNanos) - nanoAnchor), TimeUnit.NANOSECONDS);
            }

            @Override
            public void finish(int responseBytes, Throwable error) {
                if (!finished.compareAndSet(false, true)) return;
                span.setAttribute("rpc.response.size", responseBytes);
                if (error == null) {
                    span.setStatus(StatusCode.OK);
                } else {
                    span.setAttribute(AttributeKey.stringKey("error.type"), error.getClass().getName());
                    span.setStatus(StatusCode.ERROR, error.getClass().getSimpleName());
                    span.recordException(error);
                }
                span.end();
            }
        };
    }
}
