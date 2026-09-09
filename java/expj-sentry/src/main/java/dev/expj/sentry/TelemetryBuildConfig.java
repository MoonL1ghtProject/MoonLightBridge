package dev.expj.sentry;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

record TelemetryBuildConfig(
    double traceSampleRate,
    double profileSampleRate,
    boolean successLogs,
    boolean debug,
    String environment,
    String release
) {
    private static final String RESOURCE = "/META-INF/expj/telemetry.properties";

    static TelemetryBuildConfig load() {
        Properties properties = new Properties();
        try (InputStream input = TelemetryBuildConfig.class.getResourceAsStream(RESOURCE)) {
            if (input == null) return minimal();
            properties.load(input);
            return new TelemetryBuildConfig(
                rate(properties, "traceSampleRate", 0.001),
                rate(properties, "profileSampleRate", 0.0),
                Boolean.parseBoolean(properties.getProperty("successLogs", "false")),
                Boolean.parseBoolean(properties.getProperty("debug", "false")),
                properties.getProperty("environment", "production"),
                properties.getProperty("release", "expj@unknown"));
        } catch (IOException | IllegalArgumentException error) {
            System.getLogger("dev.expj.telemetry").log(
                System.Logger.Level.WARNING,
                "Invalid embedded EXPJ telemetry configuration; using minimal policy",
                error);
            return minimal();
        }
    }

    private static double rate(Properties properties, String name, double fallback) {
        String value = properties.getProperty(name);
        if (value == null) return fallback;
        double parsed = Double.parseDouble(value);
        if (Double.isNaN(parsed) || parsed < 0.0 || parsed > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 1.0");
        }
        return parsed;
    }

    private static TelemetryBuildConfig minimal() {
        return new TelemetryBuildConfig(0.001, 0.0, false, false, "production", "expj@unknown");
    }
}
