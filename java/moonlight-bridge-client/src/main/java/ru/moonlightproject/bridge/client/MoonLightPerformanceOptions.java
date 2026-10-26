package ru.moonlightproject.bridge.client;

/**
 * Writer queue, burst coalescing, buffer-reuse, and response-completion settings for one direct
 * connection.
 *
 * @param outgoingQueueCapacity maximum frames waiting for the dedicated writer
 * @param maxBatchFrames maximum already-queued frames coalesced into one write
 * @param maxBatchBytes maximum encoded bytes coalesced into one write
 * @param bufferPooling whether temporary frame buffers may be reused
 * @param responseCompletion thread on which unary response futures are completed
 */
public record MoonLightPerformanceOptions(
    int outgoingQueueCapacity,
    int maxBatchFrames,
    int maxBatchBytes,
    boolean bufferPooling,
    ResponseCompletion responseCompletion
) {
    /** Where a received unary response completes its {@code CompletableFuture}. */
    public enum ResponseCompletion {
        /**
         * Completes each response on its own virtual thread. Dependent stages that block cannot
         * stall the connection reader. This is the default.
         */
        ISOLATED,
        /**
         * Completes responses on the connection reader thread. This saves one thread hand-off per
         * call, but non-async dependent stages then run on the reader: they must not block, or
         * every response on the connection waits behind them. Paper/Folia scheduler callbacks
         * ({@code whenCompleteFor}, {@code whenCompleteAt}, {@code whenCompleteOnGlobal}) only
         * schedule work and are safe.
         */
        DIRECT
    }

    /** Validates the bounded queue and write-coalescing limits. */
    public MoonLightPerformanceOptions {
        if (outgoingQueueCapacity < 16) throw new IllegalArgumentException("outgoingQueueCapacity must be at least 16");
        if (maxBatchFrames < 1) throw new IllegalArgumentException("maxBatchFrames must be positive");
        if (maxBatchBytes < 1024) throw new IllegalArgumentException("maxBatchBytes must be at least 1024");
        java.util.Objects.requireNonNull(responseCompletion, "responseCompletion");
    }

    /**
     * Creates settings with {@link ResponseCompletion#ISOLATED} response completion.
     *
     * @param outgoingQueueCapacity maximum frames waiting for the dedicated writer
     * @param maxBatchFrames maximum already-queued frames coalesced into one write
     * @param maxBatchBytes maximum encoded bytes coalesced into one write
     * @param bufferPooling whether temporary frame buffers may be reused
     */
    public MoonLightPerformanceOptions(
        int outgoingQueueCapacity, int maxBatchFrames, int maxBatchBytes, boolean bufferPooling
    ) {
        this(outgoingQueueCapacity, maxBatchFrames, maxBatchBytes, bufferPooling,
            ResponseCompletion.ISOLATED);
    }

    /**
     * Returns a copy with a different response-completion mode.
     *
     * @param mode response-completion mode
     * @return updated settings
     */
    public MoonLightPerformanceOptions withResponseCompletion(ResponseCompletion mode) {
        return new MoonLightPerformanceOptions(
            outgoingQueueCapacity, maxBatchFrames, maxBatchBytes, bufferPooling, mode);
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
     * Disables multi-frame coalescing and completes responses directly on the reader thread to
     * minimize latency under light load. Dependent stages must not block; see
     * {@link ResponseCompletion#DIRECT}.
     *
     * @return lowest-latency preset
     */
    public static MoonLightPerformanceOptions lowestLatency() {
        return new MoonLightPerformanceOptions(1024, 1, 64 * 1024, true, ResponseCompletion.DIRECT);
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
