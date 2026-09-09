package dev.expj.sentry;

import dev.expj.client.ExpjTelemetry;
import dev.expj.client.ExpjTraceContext;
import io.sentry.ISpan;
import io.sentry.Sentry;
import io.sentry.SentryTraceHeader;
import io.sentry.SpanStatus;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/** Bridges active Sentry transactions across an EXPJ request without logging payload contents. */
public final class SentryExpjTelemetry implements ExpjTelemetry {
    private static final HexFormat HEX = HexFormat.of();
    private final double traceSampleRate;
    private final boolean logsEnabled;

    public SentryExpjTelemetry() {
        this(0.01, false);
    }

    public SentryExpjTelemetry(double traceSampleRate) {
        this(traceSampleRate, false);
    }

    public SentryExpjTelemetry(double traceSampleRate, boolean logsEnabled) {
        if (traceSampleRate < 0 || traceSampleRate > 1 || Double.isNaN(traceSampleRate)) {
            throw new IllegalArgumentException("traceSampleRate must be between 0 and 1");
        }
        this.traceSampleRate = traceSampleRate;
        this.logsEnabled = logsEnabled;
    }

    @Override
    public void connectionOpened() {
        Sentry.addBreadcrumb("EXPJ connection established");
        if (logsEnabled) Sentry.logger().info("EXPJ connection established");
    }

    @Override
    public void connectionClosed(Throwable cause) {
        Sentry.addBreadcrumb("EXPJ connection closed: " + cause.getClass().getSimpleName());
        boolean expected = "EXPJ client closed".equals(cause.getMessage());
        if (logsEnabled) {
            if (expected) {
                Sentry.logger().info("EXPJ connection closed");
            } else {
                Sentry.logger().warn(
                    "EXPJ connection closed unexpectedly; error={}",
                    cause.getClass().getSimpleName());
            }
        }
        if (!expected) {
            Sentry.captureException(cause);
        }
    }

    @Override
    public RequestObservation startRequest(RequestInfo request) {
        ISpan parent = Sentry.getSpan();
        ISpan span = null;
        if (parent != null && !parent.isNoOp()) {
            span = parent.startChild(
                "rpc.client", "EXPJ method " + Integer.toUnsignedString(request.methodId()));
        } else if (traceSampleRate > 0
            && (traceSampleRate >= 1 || ThreadLocalRandom.current().nextDouble() < traceSampleRate)) {
            span = Sentry.startTransaction(
                "EXPJ method " + Integer.toUnsignedString(request.methodId()), "rpc.client");
        }
        if (span == null && !logsEnabled) return NoTrace.INSTANCE;

        ExpjTraceContext context = null;
        if (span != null) {
            span.setData("rpc.system", "expj");
            span.setData("rpc.method_id", Integer.toUnsignedString(request.methodId()));
            span.setData("expj.request_id", Long.toUnsignedString(request.requestId()));
            span.setData("expj.request_bytes", request.requestBytes());
            SentryTraceHeader header = span.toSentryTrace();
            if (Boolean.TRUE.equals(header.isSampled())) {
                context = new ExpjTraceContext(
                    HEX.parseHex(header.getTraceId().toString()),
                    HEX.parseHex(header.getSpanId().toString()),
                    true
                );
            }
        }
        return new SentryObservation(span, context, request, System.nanoTime(), logsEnabled);
    }

    private record SentryObservation(
        ISpan span,
        ExpjTraceContext traceContext,
        RequestInfo request,
        long startedNanos,
        boolean logsEnabled
    )
        implements RequestObservation {
        @Override
        public void finish(int responseBytes, Throwable error) {
            long durationMicros = (System.nanoTime() - startedNanos) / 1_000;
            if (span != null) span.setData("expj.response_bytes", responseBytes);
            if (error == null) {
                if (span != null) span.finish(SpanStatus.OK);
                if (logsEnabled) {
                    Sentry.logger().debug(
                        "EXPJ request completed; method={} request={} duration_us={} response_bytes={}",
                        Integer.toUnsignedString(request.methodId()),
                        Long.toUnsignedString(request.requestId()),
                        durationMicros,
                        responseBytes);
                }
            } else {
                if (span != null) {
                    span.setThrowable(error);
                    span.finish(error instanceof java.util.concurrent.TimeoutException
                        ? SpanStatus.DEADLINE_EXCEEDED : SpanStatus.INTERNAL_ERROR);
                }
                if (logsEnabled) {
                    Sentry.logger().warn(
                        "EXPJ request failed; method={} request={} duration_us={} error={}",
                        Integer.toUnsignedString(request.methodId()),
                        Long.toUnsignedString(request.requestId()),
                        durationMicros,
                        error.getClass().getSimpleName());
                }
            }
        }
    }

    private enum NoTrace implements RequestObservation {
        INSTANCE;
        @Override public ExpjTraceContext traceContext() { return null; }
        @Override public void finish(int responseBytes, Throwable error) { }
    }
}
