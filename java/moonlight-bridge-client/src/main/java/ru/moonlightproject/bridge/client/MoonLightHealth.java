package ru.moonlightproject.bridge.client;

import java.time.Duration;

/**
 * A framework-level readiness snapshot returned without invoking application code.
 *
 * @param protocolVersion negotiated wire-protocol version
 * @param ready whether the backend accepts application requests
 * @param activeConnections current accepted bridge connections
 * @param activeRequests current requests executing across all connections
 * @param maxInFlightPerConnection negotiated request limit per connection
 * @param uptime elapsed backend process time
 */
public record MoonLightHealth(
    int protocolVersion,
    boolean ready,
    long activeConnections,
    long activeRequests,
    long maxInFlightPerConnection,
    Duration uptime
) { }
