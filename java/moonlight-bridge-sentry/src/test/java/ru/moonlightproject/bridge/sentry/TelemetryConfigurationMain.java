package ru.moonlightproject.bridge.sentry;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import io.sentry.KeyValueCollectionBehavior;

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
        var data = options.getDataCollection();
        if (!Boolean.FALSE.equals(data.getUserInfo())
            || !Boolean.FALSE.equals(data.getDatabaseQueryData())
            || !Boolean.FALSE.equals(data.getFilePaths())
            || !Boolean.FALSE.equals(data.getGraphql().getDocument())
            || !Boolean.FALSE.equals(data.getGraphql().getVariables())
            || data.getHttpBodies() == null
            || !data.getHttpBodies().isEmpty()
            || data.getCookies() == null
            || data.getCookies().getMode() != KeyValueCollectionBehavior.Mode.OFF
            || data.getUrlQueryParams() == null
            || data.getUrlQueryParams().getMode() != KeyValueCollectionBehavior.Mode.OFF
            || data.getHttpHeaders().getRequest() == null
            || data.getHttpHeaders().getRequest().getMode() != KeyValueCollectionBehavior.Mode.OFF
            || data.getHttpHeaders().getResponse() == null
            || data.getHttpHeaders().getResponse().getMode() != KeyValueCollectionBehavior.Mode.OFF) {
            throw new AssertionError("embedded provider did not explicitly disable sensitive data collection");
        }
        Sentry.close();
        System.out.println("Embedded telemetry uses the private Java destination without PII");
    }
}
