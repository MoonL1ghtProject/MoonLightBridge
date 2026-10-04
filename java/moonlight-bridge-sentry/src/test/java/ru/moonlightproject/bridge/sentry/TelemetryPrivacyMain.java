package ru.moonlightproject.bridge.sentry;

import io.sentry.Sentry;
import io.sentry.SentryEvent;
import ru.moonlightproject.bridge.client.MoonLightClient;
import ru.moonlightproject.bridge.client.MoonLightTelemetry;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Ensures application exception text is never submitted to the framework telemetry destination. */
public final class TelemetryPrivacyMain {
    public static void main(String[] args) throws Exception {
        var events = new ArrayBlockingQueue<SentryEvent>(8);
        Sentry.init(options -> {
            options.setDsn("https://public@localhost/1");
            options.setEnableUncaughtExceptionHandler(false);
            options.setBeforeSend((event, hint) -> { events.offer(event); return null; });
        });
        try {
            var telemetry = new SentryMoonLightTelemetry(0.0, false);
            telemetry.startRequest(new MoonLightTelemetry.RequestInfo(7, 11, 13)).finish(0,
                new MoonLightClient.MoonLightRemoteException(7, MoonLightClient.ErrorCode.INTERNAL,
                    "secret-token=do-not-send"));
            assertSafe(events.poll(5, TimeUnit.SECONDS));
            telemetry.connectionClosed(new IOException("password=do-not-send"));
            assertSafe(events.poll(5, TimeUnit.SECONDS));
        } finally { Sentry.close(); }
        System.out.println("Framework error telemetry contains no original exception or secret message");
    }

    private static void assertSafe(SentryEvent event) {
        if (event == null) throw new AssertionError("missing error event");
        if (event.getThrowable() != null || (event.getExceptions() != null && !event.getExceptions().isEmpty())) {
            throw new AssertionError("original exception leaked into framework event");
        }
        if (event.getMessage() == null || !event.getMessage().getFormatted().startsWith("MoonLightBridge")) {
            throw new AssertionError("expected a constant framework diagnostic");
        }
    }
}
