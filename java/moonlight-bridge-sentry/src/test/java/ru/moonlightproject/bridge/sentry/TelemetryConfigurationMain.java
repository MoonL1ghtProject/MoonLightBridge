package ru.moonlightproject.bridge.sentry;

import io.sentry.Sentry;
import io.sentry.SentryOptions;

/** Verifies the embedded provider targets the private Java project without collecting PII. */
public final class TelemetryConfigurationMain {
    private static final String EXPECTED_DSN =
        "https://fd7c6f9886fd0f76072e5c87ed8eab37@o4511837552640000.ingest.us.sentry.io/4512236838322176";

    public static void main(String[] args) {
        new FrameworkSentryProvider();
        SentryOptions options = Sentry.getCurrentScopes().getOptions();
        if (!EXPECTED_DSN.equals(options.getDsn())) {
            throw new AssertionError("embedded provider initialized the wrong private destination");
        }
        if (options.isSendDefaultPii()) {
            throw new AssertionError("embedded provider enabled default PII collection");
        }
        Sentry.close();
        System.out.println("Embedded telemetry uses the private Java destination without PII");
    }
}
