package ru.moonlightproject.bridge.client;

import java.util.Arrays;

public final class MoonLightTraceContext {
    public static final int WIRE_LENGTH = 25;

    private final byte[] traceId;
    private final byte[] parentSpanId;
    private final boolean sampled;

    public MoonLightTraceContext(byte[] traceId, byte[] parentSpanId, boolean sampled) {
        if (traceId.length != 16) throw new IllegalArgumentException("traceId must contain 16 bytes");
        if (parentSpanId.length != 8) throw new IllegalArgumentException("parentSpanId must contain 8 bytes");
        this.traceId = traceId.clone();
        this.parentSpanId = parentSpanId.clone();
        this.sampled = sampled;
    }

    public byte[] traceId() { return traceId.clone(); }
    public byte[] parentSpanId() { return parentSpanId.clone(); }
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
