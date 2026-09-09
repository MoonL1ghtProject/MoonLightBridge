package dev.expj.sentry;

import dev.expj.client.ExpjTelemetry;
import dev.expj.client.ExpjTelemetryProvider;
import io.sentry.ITransaction;
import io.sentry.ProfileLifecycle;
import io.sentry.Sentry;

/** Internal provider discovered automatically when expj-sentry is bundled. */
public final class FrameworkSentryProvider implements ExpjTelemetryProvider {
    private static final String FRAMEWORK_DSN =
        "https://53d54adb173ccd2d0cbc2ac5f3fa5495@o4511248228941824.ingest.us.sentry.io/4511286171664384";
    private static final TelemetryBuildConfig CONFIG = TelemetryBuildConfig.load();

    static {
        Thread thread = Thread.currentThread();
        ClassLoader previousLoader = thread.getContextClassLoader();
        // Sentry's profiling service loader also uses the thread context loader.
        // Temporarily point it at the plugin/library loader so shaded providers
        // are discoverable under Paper's classloader isolation.
        thread.setContextClassLoader(FrameworkSentryProvider.class.getClassLoader());
        try {
            Sentry.init(options -> {
                options.setDsn(FRAMEWORK_DSN);
                options.setEnvironment(CONFIG.environment());
                options.setRelease(CONFIG.release());
                // EXPJ makes its own cheap sampling decision before creating a transaction.
                options.setTracesSampleRate(1.0);
                options.setProfileSessionSampleRate(CONFIG.profileSampleRate());
                options.setProfileLifecycle(ProfileLifecycle.TRACE);
                options.getLogs().setEnabled(CONFIG.successLogs());
                // The SDK default of 30 drops telemetry during even a small EXPJ
                // batch. Keep the queue bounded, but sized for framework bursts.
                options.setMaxQueueSize(256);
                options.setSendDefaultPii(false);
                options.setDebug(CONFIG.debug());
                options.setEnableUncaughtExceptionHandler(false);
            });
            // Loading and starting async-profiler can be expensive once. Pay that
            // cost while the EXPJ connection is being established, never in RPC #1.
            if (CONFIG.profileSampleRate() > 0.0) {
                ITransaction warmup = Sentry.startTransaction("EXPJ profiler warmup", "internal");
                warmup.finish();
            }
        } finally {
            thread.setContextClassLoader(previousLoader);
        }
    }

    @Override
    public ExpjTelemetry createTelemetry() {
        return new SentryExpjTelemetry(CONFIG.traceSampleRate(), CONFIG.successLogs());
    }
}
