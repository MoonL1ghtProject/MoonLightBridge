package dev.expj.sentry;

import dev.expj.client.ExpjTelemetry;
import dev.expj.client.ExpjTraceContext;
import io.sentry.ISpan;
import io.sentry.Sentry;
import io.sentry.SentryDate;
import io.sentry.SentryTraceHeader;
import io.sentry.SpanStatus;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Bridges active Sentry transactions across an EXPJ request without logging payload contents. */
public final class SentryExpjTelemetry implements ExpjTelemetry {
    private static final HexFormat HEX = HexFormat.of();
    private static final ThreadPoolExecutor FINISHER = new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4_096),
        Thread.ofPlatform().daemon().name("expj-sentry-finish").factory(),
        new ThreadPoolExecutor.CallerRunsPolicy());
    private final double traceSampleRate;
    private final boolean logsEnabled;

    public SentryExpjTelemetry() {
        this(0.001, false);
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
        boolean sampled = traceSampleRate >= 1
            || (traceSampleRate > 0
                && ThreadLocalRandom.current().nextDouble() < traceSampleRate);
        // Looking up the current Sentry scope is substantially more expensive
        // than EXPJ's sampling decision. Do it only for requests selected by
        // the embedded framework policy.
        ISpan parent = sampled ? Sentry.getSpan() : null;
        ISpan span = null;
        if (parent != null && !parent.isNoOp()) {
            span = parent.startChild(
                "rpc.client", "EXPJ method " + Integer.toUnsignedString(request.methodId()));
        } else if (sampled) {
            span = Sentry.startTransaction(
                "EXPJ method " + Integer.toUnsignedString(request.methodId()), "rpc.client");
        }
        if (span == null && !logsEnabled) {
            return new ErrorOnlyObservation(request, System.nanoTime());
        }

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
            SentryDate completedAt = Sentry.getCurrentScopes().getOptions().getDateProvider().now();
            FINISHER.execute(() -> finishOffTransportThread(
                span, request, responseBytes, error, durationMicros, completedAt, logsEnabled));
        }
    }

    private static void finishOffTransportThread(
        ISpan span,
        RequestInfo request,
        int responseBytes,
        Throwable error,
        long durationMicros,
        SentryDate completedAt,
        boolean logsEnabled
    ) {
        if (span != null) span.setData("expj.response_bytes", responseBytes);
        if (error == null) {
            if (span != null) span.finish(SpanStatus.OK, completedAt);
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
                    ? SpanStatus.DEADLINE_EXCEEDED : SpanStatus.INTERNAL_ERROR, completedAt);
            }
            if (logsEnabled) {
                Sentry.logger().warn(
                    "EXPJ request failed; method={} request={} duration_us={} error={}",
                    Integer.toUnsignedString(request.methodId()),
                    Long.toUnsignedString(request.requestId()),
                    durationMicros,
                    error.getClass().getSimpleName());
            }
            captureError(error, request, durationMicros);
        }
    }

    private static void captureError(Throwable error, RequestInfo request, long durationMicros) {
        Sentry.captureException(error, scope -> {
            scope.setTag("rpc.system", "expj");
            scope.setTag("rpc.method_id", Integer.toUnsignedString(request.methodId()));
            scope.setExtra("expj.request_id", Long.toUnsignedString(request.requestId()));
            scope.setExtra("expj.request_bytes", Integer.toString(request.requestBytes()));
            scope.setExtra("expj.duration_us", Long.toString(durationMicros));
        });
    }

    private record ErrorOnlyObservation(RequestInfo request, long startedNanos)
        implements RequestObservation {
        @Override
        public ExpjTraceContext traceContext() { return null; }

        @Override
        public void finish(int responseBytes, Throwable error) {
            if (error == null) return;
            long durationMicros = (System.nanoTime() - startedNanos) / 1_000;
            FINISHER.execute(() -> captureError(error, request, durationMicros));
        }
    }
}
