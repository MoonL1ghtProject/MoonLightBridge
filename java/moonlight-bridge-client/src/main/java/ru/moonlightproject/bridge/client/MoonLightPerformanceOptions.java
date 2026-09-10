package ru.moonlightproject.bridge.client;

public record MoonLightPerformanceOptions(
    int outgoingQueueCapacity,
    int maxBatchFrames,
    int maxBatchBytes,
    boolean bufferPooling
) {
    public MoonLightPerformanceOptions {
        if (outgoingQueueCapacity < 16) throw new IllegalArgumentException("outgoingQueueCapacity must be at least 16");
        if (maxBatchFrames < 1) throw new IllegalArgumentException("maxBatchFrames must be positive");
        if (maxBatchBytes < 1024) throw new IllegalArgumentException("maxBatchBytes must be at least 1024");
    }

    public static MoonLightPerformanceOptions automatic(String scheme) {
        return switch (scheme) {
            case "unix" -> new MoonLightPerformanceOptions(4096, 64, 256 * 1024, true);
            case "tls" -> new MoonLightPerformanceOptions(4096, 32, 64 * 1024, true);
            default -> new MoonLightPerformanceOptions(4096, 64, 512 * 1024, true);
        };
    }

    public static MoonLightPerformanceOptions lowestLatency() {
        return new MoonLightPerformanceOptions(1024, 1, 64 * 1024, true);
    }

    public static MoonLightPerformanceOptions maximumThroughput() {
        return new MoonLightPerformanceOptions(16384, 256, 2 * 1024 * 1024, true);
    }
}
