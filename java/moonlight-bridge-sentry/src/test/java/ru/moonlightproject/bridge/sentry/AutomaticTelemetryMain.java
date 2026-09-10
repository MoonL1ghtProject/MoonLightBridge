package ru.moonlightproject.bridge.sentry;

import ru.moonlightproject.bridge.client.MoonLightTelemetry;

public final class AutomaticTelemetryMain {
    public static void main(String[] args) {
        Thread thread = Thread.currentThread();
        ClassLoader previousLoader = thread.getContextClassLoader();
        // Simulate Paper's server thread, whose context loader cannot see the
        // plugin's shaded classes or META-INF/services entries.
        thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        MoonLightTelemetry automatic;
        try {
            automatic = MoonLightTelemetry.automatic();
        } finally {
            thread.setContextClassLoader(previousLoader);
        }
        if (automatic == MoonLightTelemetry.disabled()) {
            throw new AssertionError("bundled MoonLightBridge Sentry provider was not discovered");
        }
        if (automatic != MoonLightTelemetry.automatic()) {
            throw new AssertionError("automatic telemetry must initialize exactly once");
        }
        // Logs must remain active even when trace sampling rejects every request.
        var logOnly = new SentryMoonLightTelemetry(0.0, true);
        var observation = logOnly.startRequest(new MoonLightTelemetry.RequestInfo(7, 11, 13));
        if (observation == null || observation.traceContext() != null) {
            throw new AssertionError("log-only observations must not depend on trace sampling");
        }
        observation.finish(17, null);
        System.out.println("MoonLightBridge Java Sentry provider discovered automatically");
    }
}
