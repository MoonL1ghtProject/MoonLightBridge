/**
 * Application-owned Micrometer metrics for MoonLightBridge Java and Paper clients.
 *
 * <p>The adapter registers bounded-cardinality aggregate meters in a caller-supplied registry. It
 * opens no listener, exports no anonymous product statistics, and does not attach request IDs or
 * payload contents as metric labels.</p>
 */
package ru.moonlightproject.bridge.micrometer;
