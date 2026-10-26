package ru.moonlightproject.bridge.client;

import java.util.Arrays;
import java.util.ServiceLoader;

/**
 * Vendor-neutral instrumentation SPI used around connection and request lifecycle.
 *
 * <p>Generated clients provide a canonical {@code Service/Method} name and may propagate the
 * returned trace context to the Rust server. The SPI never receives application payload contents.</p>
 */
public interface MoonLightTelemetry {
    /** From the end of request encoding until the frame is queued: admission, interceptors, span start. */
    String STAGE_PREPARE = "moonlight.client.prepare";
    /** From the queued frame until it is flushed to the socket, including writer queue wait. */
    String STAGE_WRITE = "moonlight.client.write";
    /** From the flushed request until the response frame is read: network plus server time. */
    String STAGE_WAIT = "moonlight.client.wait";
    /** From the read response frame until its completion runs (the completion thread hand-off). */
    String STAGE_DISPATCH = "moonlight.client.dispatch";

    /**
     * Starts one request observation; returning {@code null} skips instrumentation.
     *
     * @param request stable request metadata without application payload contents
     * @return observation completed by the response path, or {@code null}
     */
    RequestObservation startRequest(RequestInfo request);

    /**
     * Starts a named child function observation.
     *
     * @param name stable function or stage name
     * @return closeable observation, normally {@link FunctionObservation#DISABLED}
     */
    default FunctionObservation startFunction(String name) { return FunctionObservation.DISABLED; }

    /**
     * Starts a framework-owned span for an application function or serialization stage.
     *
     * @param name stable function or stage name
     * @return closeable observation loaded from the bundled provider
     */
    static FunctionObservation function(String name) {
        return Automatic.INSTANCE.startFunction(name);
    }

    /**
     * Executes a synchronous function inside an automatically completed observation.
     *
     * @param name stable function or stage name
     * @param function operation to execute
     * @param <T> returned value type
     * @param <E> checked error type declared by the operation
     * @return operation result
     * @throws E when the operation fails
     */
    static <T, E extends Throwable> T traceFunction(
        String name, ThrowingSupplier<T, E> function
    ) throws E {
        try (FunctionObservation observation = function(name)) {
            try {
                return function.get();
            } catch (Throwable error) {
                observation.failed(error);
                throw error;
            }
        }
    }

    /** Reports a successful protocol handshake. */
    default void connectionOpened() { }
    /**
     * Reports direct connection termination.
     *
     * @param cause close or transport failure reason
     */
    default void connectionClosed(Throwable cause) { }

    /** Returns the allocation-free no-op telemetry implementation.
     * @return singleton disabled implementation
     */
    static MoonLightTelemetry disabled() { return Disabled.INSTANCE; }

    /** Loads bundled MoonLightBridge-owned telemetry providers once; returns no-op when none are present.
     * @return automatically discovered instrumentation
     */
    static MoonLightTelemetry automatic() { return Automatic.INSTANCE; }

    /**
     * Combines non-null telemetry implementations into one fan-out adapter.
     *
     * @param delegates local instrumentation implementations
     * @return one implementation forwarding to every non-null delegate
     */
    static MoonLightTelemetry composite(MoonLightTelemetry... delegates) {
        MoonLightTelemetry[] copy = Arrays.stream(delegates)
            .filter(delegate -> delegate != null && delegate != Disabled.INSTANCE)
            .toArray(MoonLightTelemetry[]::new);
        if (copy.length == 0) return disabled();
        if (copy.length == 1) return copy[0];
        return new Composite(copy);
    }

    /**
     * Stable metadata known when the client submits a request.
     *
     * @param methodId stable generated method identifier
     * @param requestId connection-local request identifier
     * @param requestBytes encoded application payload size
     * @param methodName generated canonical {@code Service/Method} name, or {@code null} for raw
     *                   calls
     * @param startNanos {@link System#nanoTime()} when the call started, before request encoding
     */
    record RequestInfo(
        int methodId, long requestId, int requestBytes, String methodName, long startNanos
    ) {
        /**
         * Creates metadata for a call that starts now.
         *
         * @param methodId stable generated method identifier
         * @param requestId connection-local request identifier
         * @param requestBytes encoded application payload size
         * @param methodName generated canonical {@code Service/Method} name, or {@code null}
         */
        public RequestInfo(int methodId, long requestId, int requestBytes, String methodName) {
            this(methodId, requestId, requestBytes, methodName, System.nanoTime());
        }

        /** Creates metadata for a raw call whose method name is unavailable.
         * @param methodId stable generated method identifier
         * @param requestId connection-local request identifier
         * @param requestBytes encoded application payload size
         */
        public RequestInfo(int methodId, long requestId, int requestBytes) {
            this(methodId, requestId, requestBytes, null);
        }
    }

    /** Per-request instrumentation completed by the response or failure path. */
    interface RequestObservation {
        /** Returns optional wire trace context to propagate to Rust.
         * @return trace context, or {@code null} when this request is not sampled
         */
        MoonLightTraceContext traceContext();
        /**
         * Completes the request observation.
         *
         * @param responseBytes encoded successful response size, or zero on failure
         * @param error completion failure, or {@code null} for success
         */
        void finish(int responseBytes, Throwable error);

        /**
         * Records a completed stage of this request with real start and end times.
         *
         * <p>The client reports codec stages ({@code protobuf.encode.*}, {@code protobuf.decode.*})
         * and transport stages ({@link #STAGE_PREPARE}, {@link #STAGE_WRITE}, {@link #STAGE_WAIT},
         * {@link #STAGE_DISPATCH}) before {@link #finish}. Tracing adapters export them as child
         * spans so a waterfall shows where the call spent its time.</p>
         *
         * @param name stable stage name
         * @param startNanos {@link System#nanoTime()} when the stage started
         * @param endNanos {@link System#nanoTime()} when the stage ended
         */
        default void recordStage(String name, long startNanos, long endNanos) { }

        /**
         * Returns whether this request wants stage timing.
         *
         * <p>The client only collects stage timestamps when this returns {@code true}. Tracing
         * adapters return {@code true} for sampled requests only, so unsampled calls pay nothing.</p>
         *
         * @return {@code true} to receive {@link #recordStage} calls
         */
        default boolean wantsStages() { return false; }
    }

    /** Auto-closeable child stage for generated codecs and application functions. */
    interface FunctionObservation extends AutoCloseable {
        /** Allocation-free disabled observation. */
        FunctionObservation DISABLED = new FunctionObservation() { };
        /**
         * Marks the observed function as failed.
         *
         * @param error function failure
         */
        default void failed(Throwable error) { }
        @Override default void close() { }
    }

    /**
     * Supplier whose operation may throw a checked exception.
     *
     * @param <T> supplied value type
     * @param <E> declared checked error type
     */
    @FunctionalInterface
    interface ThrowingSupplier<T, E extends Throwable> {
        /**
         * Computes the result or throws the declared error type.
         *
         * @return computed result
         * @throws E declared operation failure
         */
        T get() throws E;
    }

    /** Internal singleton no-op implementation exposed through {@link #disabled()}. */
    final class Disabled implements MoonLightTelemetry, RequestObservation {
        private static final Disabled INSTANCE = new Disabled();
        private Disabled() { }
        @Override public RequestObservation startRequest(RequestInfo request) { return this; }
        @Override public MoonLightTraceContext traceContext() { return null; }
        @Override public void finish(int responseBytes, Throwable error) { }
    }

    /** Internal holder that loads bundled providers once. */
    final class Automatic {
        private static final System.Logger LOGGER = System.getLogger("ru.moonlightproject.bridge.telemetry");
        private static final MoonLightTelemetry INSTANCE = load();
        private Automatic() { }

        private static MoonLightTelemetry load() {
            try {
                // Paper plugins live in isolated classloaders. The server thread's
                // context loader generally cannot see a shaded provider.
                return composite(ServiceLoader.load(
                        MoonLightTelemetryProvider.class,
                        MoonLightTelemetryProvider.class.getClassLoader())
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .map(MoonLightTelemetryProvider::createTelemetry)
                    .toArray(MoonLightTelemetry[]::new));
            } catch (RuntimeException | java.util.ServiceConfigurationError | LinkageError error) {
                LOGGER.log(System.Logger.Level.WARNING,
                    "MoonLightBridge telemetry provider failed to initialize; tracing and profiling are disabled", error);
                return disabled();
            }
        }
    }

    /** Internal fan-out implementation returned by {@link #composite}. */
    final class Composite implements MoonLightTelemetry {
        private final MoonLightTelemetry[] delegates;
        private Composite(MoonLightTelemetry[] delegates) { this.delegates = delegates; }

        @Override
        public RequestObservation startRequest(RequestInfo request) {
            RequestObservation[] observations = Arrays.stream(delegates)
                .map(delegate -> delegate.startRequest(request))
                .filter(observation -> observation != null)
                .toArray(RequestObservation[]::new);
            return new RequestObservation() {
                @Override
                public MoonLightTraceContext traceContext() {
                    for (RequestObservation observation : observations) {
                        MoonLightTraceContext context = observation.traceContext();
                        if (context != null) return context;
                    }
                    return null;
                }

                @Override
                public void finish(int responseBytes, Throwable error) {
                    for (RequestObservation observation : observations) {
                        try { observation.finish(responseBytes, error); }
                        catch (RuntimeException ignored) { }
                    }
                }

                @Override
                public boolean wantsStages() {
                    for (RequestObservation observation : observations) {
                        if (observation.wantsStages()) return true;
                    }
                    return false;
                }

                @Override
                public void recordStage(String name, long startNanos, long endNanos) {
                    for (RequestObservation observation : observations) {
                        try { observation.recordStage(name, startNanos, endNanos); }
                        catch (RuntimeException ignored) { }
                    }
                }
            };
        }

        @Override
        public FunctionObservation startFunction(String name) {
            FunctionObservation[] observations = Arrays.stream(delegates)
                .map(delegate -> delegate.startFunction(name))
                .filter(observation -> observation != null && observation != FunctionObservation.DISABLED)
                .toArray(FunctionObservation[]::new);
            return new FunctionObservation() {
                @Override public void failed(Throwable error) {
                    for (FunctionObservation observation : observations) observation.failed(error);
                }
                @Override public void close() {
                    for (FunctionObservation observation : observations) observation.close();
                }
            };
        }

        @Override
        public void connectionOpened() {
            for (MoonLightTelemetry delegate : delegates) delegate.connectionOpened();
        }

        @Override
        public void connectionClosed(Throwable cause) {
            for (MoonLightTelemetry delegate : delegates) delegate.connectionClosed(cause);
        }
    }
}
