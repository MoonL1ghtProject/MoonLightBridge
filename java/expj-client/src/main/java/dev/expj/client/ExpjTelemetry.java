package dev.expj.client;

import java.util.Arrays;
import java.util.ServiceLoader;

public interface ExpjTelemetry {
    RequestObservation startRequest(RequestInfo request);

    default void connectionOpened() { }
    default void connectionClosed(Throwable cause) { }

    static ExpjTelemetry disabled() { return Disabled.INSTANCE; }

    /** Loads bundled EXPJ-owned telemetry providers once; returns no-op when none are present. */
    static ExpjTelemetry automatic() { return Automatic.INSTANCE; }

    static ExpjTelemetry composite(ExpjTelemetry... delegates) {
        ExpjTelemetry[] copy = Arrays.stream(delegates)
            .filter(delegate -> delegate != null && delegate != Disabled.INSTANCE)
            .toArray(ExpjTelemetry[]::new);
        if (copy.length == 0) return disabled();
        if (copy.length == 1) return copy[0];
        return new Composite(copy);
    }

    record RequestInfo(int methodId, long requestId, int requestBytes) { }

    interface RequestObservation {
        ExpjTraceContext traceContext();
        void finish(int responseBytes, Throwable error);
    }

    final class Disabled implements ExpjTelemetry, RequestObservation {
        private static final Disabled INSTANCE = new Disabled();
        private Disabled() { }
        @Override public RequestObservation startRequest(RequestInfo request) { return this; }
        @Override public ExpjTraceContext traceContext() { return null; }
        @Override public void finish(int responseBytes, Throwable error) { }
    }

    final class Automatic {
        private static final ExpjTelemetry INSTANCE = load();
        private Automatic() { }

        private static ExpjTelemetry load() {
            try {
                return composite(ServiceLoader.load(ExpjTelemetryProvider.class)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .map(ExpjTelemetryProvider::createTelemetry)
                    .toArray(ExpjTelemetry[]::new));
            } catch (RuntimeException | java.util.ServiceConfigurationError ignored) {
                return disabled();
            }
        }
    }

    final class Composite implements ExpjTelemetry {
        private final ExpjTelemetry[] delegates;
        private Composite(ExpjTelemetry[] delegates) { this.delegates = delegates; }

        @Override
        public RequestObservation startRequest(RequestInfo request) {
            RequestObservation[] observations = Arrays.stream(delegates)
                .map(delegate -> delegate.startRequest(request))
                .filter(observation -> observation != null)
                .toArray(RequestObservation[]::new);
            return new RequestObservation() {
                @Override
                public ExpjTraceContext traceContext() {
                    for (RequestObservation observation : observations) {
                        ExpjTraceContext context = observation.traceContext();
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
            for (ExpjTelemetry delegate : delegates) delegate.connectionOpened();
        }

        @Override
        public void connectionClosed(Throwable cause) {
            for (ExpjTelemetry delegate : delegates) delegate.connectionClosed(cause);
        }
    }
}
