package dev.expj.sentry;

import dev.expj.client.ExpjTelemetry;
import dev.expj.client.ExpjTelemetryProvider;
import io.sentry.ITransaction;
import io.sentry.ProfileLifecycle;
import io.sentry.Sentry;

/** Internal provider discovered automatically when expj-sentry is bundled. */
public final class FrameworkSentryProvider implements ExpjTelemetryProvider {
    private static final String RELEASE = "expj@0.1.0-SNAPSHOT";
    private static final String FRAMEWORK_DSN =
        "https://53d54adb173ccd2d0cbc2ac5f3fa5495@o4511248228941824.ingest.us.sentry.io/4511286171664384";
    private static final boolean DEVELOPMENT_BUILD = RELEASE.endsWith("-SNAPSHOT");
    private static final double TRACE_SAMPLE_RATE = sampleRate(
        "expj.sentry.trace-sample-rate", DEVELOPMENT_BUILD ? 1.0 : 0.01);
    private static final double PROFILE_SAMPLE_RATE = sampleRate(
        "expj.sentry.profile-sample-rate", DEVELOPMENT_BUILD ? 1.0 : 0.0);
    private static final boolean LOGS_ENABLED = Boolean.parseBoolean(System.getProperty(
        "expj.sentry.logs", Boolean.toString(DEVELOPMENT_BUILD)));

    static {
        Sentry.init(options -> {
            options.setDsn(FRAMEWORK_DSN);
            options.setEnvironment(System.getProperty(
                "expj.environment", DEVELOPMENT_BUILD ? "development" : "production"));
            options.setRelease(RELEASE);
            // EXPJ makes its own cheap sampling decision before creating a transaction.
            options.setTracesSampleRate(1.0);
            options.setProfileSessionSampleRate(PROFILE_SAMPLE_RATE);
            options.setProfileLifecycle(ProfileLifecycle.TRACE);
            options.getLogs().setEnabled(LOGS_ENABLED);
            // The SDK default of 30 drops telemetry during even a small EXPJ
            // batch. Keep the queue bounded, but sized for framework bursts.
            options.setMaxQueueSize(DEVELOPMENT_BUILD ? 4_096 : 256);
            options.setSendDefaultPii(false);
            options.setDebug(Boolean.getBoolean("expj.sentry.debug"));
            options.setEnableUncaughtExceptionHandler(false);
        });
        // Loading and starting async-profiler can be expensive once. Pay that
        // cost while the EXPJ connection is being established, never in RPC #1.
        if (PROFILE_SAMPLE_RATE > 0.0) {
            ITransaction warmup = Sentry.startTransaction("EXPJ profiler warmup", "internal");
            warmup.finish();
        }
    }

    @Override
    public ExpjTelemetry createTelemetry() {
        return new SentryExpjTelemetry(TRACE_SAMPLE_RATE, LOGS_ENABLED);
    }

    private static double sampleRate(String property, double fallback) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) return fallback;
        try {
            double value = Double.parseDouble(configured);
            return value >= 0.0 && value <= 1.0 && !Double.isNaN(value) ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
