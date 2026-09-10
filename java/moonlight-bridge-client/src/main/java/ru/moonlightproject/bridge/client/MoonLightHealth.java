package ru.moonlightproject.bridge.client;

import java.time.Duration;

/** A framework-level readiness snapshot returned without invoking application code. */
public record MoonLightHealth(
    int protocolVersion,
    boolean ready,
    long activeConnections,
    long activeRequests,
    long maxInFlightPerConnection,
    Duration uptime
) { }
