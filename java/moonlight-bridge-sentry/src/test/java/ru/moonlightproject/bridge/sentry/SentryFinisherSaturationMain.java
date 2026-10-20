package ru.moonlightproject.bridge.sentry;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Ensures a saturated telemetry finisher never silently discards span completion. */
public final class SentryFinisherSaturationMain {
    public static void main(String[] args) throws Exception {
        var finisher = SentryMoonLightTelemetry.newFinisher(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completedBy = new AtomicReference<Thread>();
        try {
            finisher.execute(() -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            if (!entered.await(1, TimeUnit.SECONDS)) throw new AssertionError("finisher did not start");
            finisher.execute(() -> {});
            Thread caller = Thread.currentThread();
            finisher.execute(() -> completedBy.set(Thread.currentThread()));
            if (completedBy.get() != caller) {
                throw new AssertionError("saturated finisher silently discarded completion");
            }
        } finally {
            release.countDown();
            finisher.shutdownNow();
        }
        System.out.println("Saturated Sentry finisher preserves span completion");
    }
}
