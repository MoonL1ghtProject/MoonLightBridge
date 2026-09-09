package dev.expj.sentry;

import dev.expj.client.ExpjTelemetry;

public final class AutomaticTelemetryMain {
    public static void main(String[] args) {
        ExpjTelemetry automatic = ExpjTelemetry.automatic();
        if (automatic == ExpjTelemetry.disabled()) {
            throw new AssertionError("bundled EXPJ Sentry provider was not discovered");
        }
        if (automatic != ExpjTelemetry.automatic()) {
            throw new AssertionError("automatic telemetry must initialize exactly once");
        }
        System.out.println("EXPJ Java Sentry provider discovered automatically");
    }
}
