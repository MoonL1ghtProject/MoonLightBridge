package ru.moonlightproject.bridge.client;

import java.util.Arrays;

/** Immutable vendor-neutral distributed trace identifiers sent on the wire. */
public final class MoonLightTraceContext {
    /** Fixed encoded trace context length. */
    public static final int WIRE_LENGTH = 25;

    private final byte[] traceId;
    private final byte[] parentSpanId;
    private final boolean sampled;

    /**
     * Creates a validated trace context and defensively copies both identifiers.
     *
     * @param traceId 16-byte distributed trace identifier
     * @param parentSpanId eight-byte parent span identifier
     * @param sampled whether the originating trace was selected for recording
     */
    public MoonLightTraceContext(byte[] traceId, byte[] parentSpanId, boolean sampled) {
        if (traceId.length != 16) throw new IllegalArgumentException("traceId must contain 16 bytes");
        if (parentSpanId.length != 8) throw new IllegalArgumentException("parentSpanId must contain 8 bytes");
        this.traceId = traceId.clone();
        this.parentSpanId = parentSpanId.clone();
        this.sampled = sampled;
    }

    /** Returns a copy of the 16-byte trace identifier.
     * @return defensive trace-ID copy
     */
    public byte[] traceId() { return traceId.clone(); }
    /** Returns a copy of the eight-byte parent span identifier.
     * @return defensive parent-span-ID copy
     */
    public byte[] parentSpanId() { return parentSpanId.clone(); }
    /** Returns whether the originating trace was sampled.
     * @return sampling decision propagated on the wire
     */
    public boolean sampled() { return sampled; }

    void writeTo(java.nio.ByteBuffer target) {
        target.put(traceId).put(parentSpanId).put((byte) (sampled ? 1 : 0));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MoonLightTraceContext context
            && sampled == context.sampled
            && Arrays.equals(traceId, context.traceId)
            && Arrays.equals(parentSpanId, context.parentSpanId);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(traceId) + Arrays.hashCode(parentSpanId))
            + Boolean.hashCode(sampled);
    }
}
