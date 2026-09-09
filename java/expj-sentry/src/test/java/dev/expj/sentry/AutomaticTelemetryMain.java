package dev.expj.sentry;

import dev.expj.client.ExpjTelemetry;

public final class AutomaticTelemetryMain {
    public static void main(String[] args) {
        Thread thread = Thread.currentThread();
        ClassLoader previousLoader = thread.getContextClassLoader();
        // Simulate Paper's server thread, whose context loader cannot see the
        // plugin's shaded classes or META-INF/services entries.
        thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        ExpjTelemetry automatic;
        try {
            automatic = ExpjTelemetry.automatic();
        } finally {
            thread.setContextClassLoader(previousLoader);
        }
        if (automatic == ExpjTelemetry.disabled()) {
            throw new AssertionError("bundled EXPJ Sentry provider was not discovered");
        }
        if (automatic != ExpjTelemetry.automatic()) {
            throw new AssertionError("automatic telemetry must initialize exactly once");
        }
        // Logs must remain active even when trace sampling rejects every request.
        var logOnly = new SentryExpjTelemetry(0.0, true);
        var observation = logOnly.startRequest(new ExpjTelemetry.RequestInfo(7, 11, 13));
        if (observation == null || observation.traceContext() != null) {
            throw new AssertionError("log-only observations must not depend on trace sampling");
        }
        observation.finish(17, null);
        System.out.println("EXPJ Java Sentry provider discovered automatically");
    }
}
