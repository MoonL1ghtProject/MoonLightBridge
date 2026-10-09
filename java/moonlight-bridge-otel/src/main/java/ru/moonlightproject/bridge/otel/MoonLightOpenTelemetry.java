package ru.moonlightproject.bridge.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.moonlightproject.bridge.client.MoonLightTelemetry;
import ru.moonlightproject.bridge.client.MoonLightTraceContext;

/** Creates client spans through a caller-owned OpenTelemetry SDK. */
public final class MoonLightOpenTelemetry implements MoonLightTelemetry {
    private final io.opentelemetry.api.trace.Tracer tracer;

    /**
     * Creates telemetry without configuring exporters or opening listeners.
     *
     * @param openTelemetry caller-owned OpenTelemetry instance
     */
    public MoonLightOpenTelemetry(OpenTelemetry openTelemetry) {
        tracer = Objects.requireNonNull(openTelemetry, "openTelemetry")
            .getTracer("ru.moonlightproject.bridge", "0.5.0");
    }

    @Override
    public RequestObservation startRequest(RequestInfo request) {
        String spanName = request.methodName() == null
            ? "moonlight.rpc." + Integer.toUnsignedString(request.methodId(), 16)
            : request.methodName();
        Span span = tracer.spanBuilder(spanName)
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
