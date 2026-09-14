package ru.moonlightproject.bridge.client;

import java.util.Arrays;
import java.util.ServiceLoader;

/** Vendor-neutral instrumentation SPI used around connection and request lifecycle. */
public interface MoonLightTelemetry {
    /** Starts one request observation; returning {@code null} skips instrumentation. */
    RequestObservation startRequest(RequestInfo request);

    /** Starts a named child function observation. */
    default FunctionObservation startFunction(String name) { return FunctionObservation.DISABLED; }

    /** Starts a framework-owned span for an application function or serialization stage. */
    static FunctionObservation function(String name) {
        return Automatic.INSTANCE.startFunction(name);
    }

    /** Executes a synchronous function inside an automatically completed observation. */
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
    /** Reports direct connection termination. */
    default void connectionClosed(Throwable cause) { }

    /** Returns the allocation-free no-op telemetry implementation. */
    static MoonLightTelemetry disabled() { return Disabled.INSTANCE; }

    /** Loads bundled MoonLightBridge-owned telemetry providers once; returns no-op when none are present. */
    static MoonLightTelemetry automatic() { return Automatic.INSTANCE; }

    /** Combines non-null telemetry implementations into one fan-out adapter. */
    static MoonLightTelemetry composite(MoonLightTelemetry... delegates) {
        MoonLightTelemetry[] copy = Arrays.stream(delegates)
            .filter(delegate -> delegate != null && delegate != Disabled.INSTANCE)
            .toArray(MoonLightTelemetry[]::new);
        if (copy.length == 0) return disabled();
        if (copy.length == 1) return copy[0];
        return new Composite(copy);
    }

    /** Stable metadata known when the client submits a request. */
    record RequestInfo(int methodId, long requestId, int requestBytes) { }

    /** Per-request instrumentation completed by the response or failure path. */
    interface RequestObservation {
        /** Returns optional wire trace context to propagate to Rust. */
        MoonLightTraceContext traceContext();
        /** Completes the request observation. */
        void finish(int responseBytes, Throwable error);
    }

    /** Auto-closeable child stage for generated codecs and application functions. */
    interface FunctionObservation extends AutoCloseable {
        /** Allocation-free disabled observation. */
        FunctionObservation DISABLED = new FunctionObservation() { };
        /** Marks the observed function as failed. */
        default void failed(Throwable error) { }
        @Override default void close() { }
    }

    @FunctionalInterface
    /** Supplier whose operation may throw a checked exception. */
    interface ThrowingSupplier<T, E extends Throwable> {
        /** Computes the result or throws the declared error type. */
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
