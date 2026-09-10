package ru.moonlightproject.bridge.client;

import java.util.Arrays;
import java.util.ServiceLoader;

public interface MoonLightTelemetry {
    RequestObservation startRequest(RequestInfo request);

    default void connectionOpened() { }
    default void connectionClosed(Throwable cause) { }

    static MoonLightTelemetry disabled() { return Disabled.INSTANCE; }

    /** Loads bundled MoonLightBridge-owned telemetry providers once; returns no-op when none are present. */
    static MoonLightTelemetry automatic() { return Automatic.INSTANCE; }

    static MoonLightTelemetry composite(MoonLightTelemetry... delegates) {
        MoonLightTelemetry[] copy = Arrays.stream(delegates)
            .filter(delegate -> delegate != null && delegate != Disabled.INSTANCE)
            .toArray(MoonLightTelemetry[]::new);
        if (copy.length == 0) return disabled();
        if (copy.length == 1) return copy[0];
        return new Composite(copy);
    }

    record RequestInfo(int methodId, long requestId, int requestBytes) { }

    interface RequestObservation {
        MoonLightTraceContext traceContext();
        void finish(int responseBytes, Throwable error);
    }

    final class Disabled implements MoonLightTelemetry, RequestObservation {
        private static final Disabled INSTANCE = new Disabled();
        private Disabled() { }
        @Override public RequestObservation startRequest(RequestInfo request) { return this; }
        @Override public MoonLightTraceContext traceContext() { return null; }
        @Override public void finish(int responseBytes, Throwable error) { }
    }

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
        public void connectionOpened() {
            for (MoonLightTelemetry delegate : delegates) delegate.connectionOpened();
        }

        @Override
        public void connectionClosed(Throwable cause) {
            for (MoonLightTelemetry delegate : delegates) delegate.connectionClosed(cause);
        }
    }
}
