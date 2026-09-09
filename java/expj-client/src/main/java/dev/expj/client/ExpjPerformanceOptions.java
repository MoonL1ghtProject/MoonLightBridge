package dev.expj.client;

public record ExpjPerformanceOptions(
    int outgoingQueueCapacity,
    int maxBatchFrames,
    int maxBatchBytes,
    boolean bufferPooling
) {
    public ExpjPerformanceOptions {
        if (outgoingQueueCapacity < 16) throw new IllegalArgumentException("outgoingQueueCapacity must be at least 16");
        if (maxBatchFrames < 1) throw new IllegalArgumentException("maxBatchFrames must be positive");
        if (maxBatchBytes < 1024) throw new IllegalArgumentException("maxBatchBytes must be at least 1024");
    }

    public static ExpjPerformanceOptions automatic(String scheme) {
        return switch (scheme) {
            case "unix" -> new ExpjPerformanceOptions(4096, 64, 256 * 1024, true);
            case "tls" -> new ExpjPerformanceOptions(4096, 32, 64 * 1024, true);
            default -> new ExpjPerformanceOptions(4096, 64, 512 * 1024, true);
        };
    }

    public static ExpjPerformanceOptions lowestLatency() {
        return new ExpjPerformanceOptions(1024, 1, 64 * 1024, true);
    }

    public static ExpjPerformanceOptions maximumThroughput() {
        return new ExpjPerformanceOptions(16384, 256, 2 * 1024 * 1024, true);
    }
}
