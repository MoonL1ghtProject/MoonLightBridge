package dev.expj.client;

/** Service-provider hook used by optional, bundled EXPJ observability modules. */
public interface ExpjTelemetryProvider {
    ExpjTelemetry createTelemetry();
}
