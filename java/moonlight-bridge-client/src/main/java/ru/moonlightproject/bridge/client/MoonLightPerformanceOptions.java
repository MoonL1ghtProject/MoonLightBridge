package ru.moonlightproject.bridge.client;

/**
 * Writer queue, burst coalescing, and buffer-reuse settings for one direct connection.
 *
 * @param outgoingQueueCapacity maximum frames waiting for the dedicated writer
 * @param maxBatchFrames maximum already-queued frames coalesced into one write
 * @param maxBatchBytes maximum encoded bytes coalesced into one write
 * @param bufferPooling whether temporary frame buffers may be reused
 */
public record MoonLightPerformanceOptions(
    int outgoingQueueCapacity,
    int maxBatchFrames,
    int maxBatchBytes,
    boolean bufferPooling
) {
    /** Validates the bounded queue and write-coalescing limits. */
    public MoonLightPerformanceOptions {
        if (outgoingQueueCapacity < 16) throw new IllegalArgumentException("outgoingQueueCapacity must be at least 16");
        if (maxBatchFrames < 1) throw new IllegalArgumentException("maxBatchFrames must be positive");
        if (maxBatchBytes < 1024) throw new IllegalArgumentException("maxBatchBytes must be at least 1024");
    }

    /**
     * Chooses balanced defaults for {@code unix}, {@code tcp}, or {@code tls}.
     *
     * @param scheme endpoint transport scheme
     * @return balanced settings for the transport
     */
    public static MoonLightPerformanceOptions automatic(String scheme) {
        return switch (scheme) {
            case "unix" -> new MoonLightPerformanceOptions(4096, 64, 256 * 1024, true);
            case "tls" -> new MoonLightPerformanceOptions(4096, 32, 64 * 1024, true);
            default -> new MoonLightPerformanceOptions(4096, 64, 512 * 1024, true);
        };
    }

    /**
     * Disables multi-frame coalescing to minimize latency under light load.
     *
     * @return lowest-latency preset
     */
    public static MoonLightPerformanceOptions lowestLatency() {
        return new MoonLightPerformanceOptions(1024, 1, 64 * 1024, true);
    }

    /**
     * Uses larger bounded queues and bursts for maximum sustained throughput.
     *
     * @return maximum-throughput preset
     */
    public static MoonLightPerformanceOptions maximumThroughput() {
        return new MoonLightPerformanceOptions(16384, 256, 2 * 1024 * 1024, true);
    }
}
