package ru.moonlightproject.bridge.client;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ReconnectingMoonLightClient implements MoonLightChannel {
    private final String endpoint;
    private final ReconnectPolicy policy;
    private final MoonLightPerformanceOptions performance;
    private final MoonLightTelemetry telemetry;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<MoonLightClient> active = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger failedAttempts = new AtomicInteger();
    private final CompletableFuture<Void> firstConnection = new CompletableFuture<>();

    private ReconnectingMoonLightClient(
        String endpoint,
        ReconnectPolicy policy,
        MoonLightPerformanceOptions performance,
        MoonLightTelemetry telemetry,
        boolean connectImmediately
    ) throws IOException {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.policy = Objects.requireNonNull(policy);
        this.performance = Objects.requireNonNull(performance);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("moonlight-bridge-reconnect").factory());
        if (connectImmediately) {
            install(MoonLightClient.connect(endpoint, performance, telemetry));
        } else {
            reconnectScheduled.set(true);
            scheduler.execute(this::tryReconnect);
        }
    }

    public static ReconnectingMoonLightClient connect(String endpoint) throws IOException {
        return new ReconnectingMoonLightClient(
            endpoint, ReconnectPolicy.defaults(), automaticPerformance(endpoint), MoonLightTelemetry.automatic(), true);
    }

    public static ReconnectingMoonLightClient connect(String endpoint, ReconnectPolicy policy) throws IOException {
        return new ReconnectingMoonLightClient(
            endpoint, policy, automaticPerformance(endpoint), MoonLightTelemetry.automatic(), true);
    }

    public static ReconnectingMoonLightClient connect(
        String endpoint,
        ReconnectPolicy policy,
        MoonLightPerformanceOptions performance,
        MoonLightTelemetry telemetry
    ) throws IOException {
        return new ReconnectingMoonLightClient(endpoint, policy, performance, telemetry, true);
    }

    /**
     * Starts a supervised client without performing network I/O on the calling thread.
     * Requests fail fast until the first connection is established.
     */
    public static ReconnectingMoonLightClient start(String endpoint) throws IOException {
        return new ReconnectingMoonLightClient(
            endpoint, ReconnectPolicy.defaults(), automaticPerformance(endpoint), MoonLightTelemetry.automatic(), false);
    }

    public static ReconnectingMoonLightClient start(String endpoint, ReconnectPolicy policy) throws IOException {
        return new ReconnectingMoonLightClient(
            endpoint, policy, automaticPerformance(endpoint), MoonLightTelemetry.automatic(), false);
    }

    public static ReconnectingMoonLightClient start(
        String endpoint,
        ReconnectPolicy policy,
        MoonLightPerformanceOptions performance,
        MoonLightTelemetry telemetry
    ) throws IOException {
        return new ReconnectingMoonLightClient(endpoint, policy, performance, telemetry, false);
    }

    public boolean isConnected() { return active.get() != null; }

    /** Completes once after the first successful connection. */
    public CompletionStage<Void> firstConnection() { return firstConnection; }

    public CompletableFuture<byte[]> request(int methodId, byte[] body, Duration deadline) {
        MoonLightClient client = active.get();
        if (client == null) {
            return CompletableFuture.failedFuture(new BackendUnavailableException(endpoint));
        }
        return client.request(methodId, body, deadline);
    }

    public CompletableFuture<Void> ping(Duration timeout) {
        MoonLightClient client = active.get();
        if (client == null) {
            return CompletableFuture.failedFuture(new BackendUnavailableException(endpoint));
        }
        return client.ping(timeout);
    }

    private void install(MoonLightClient client) {
        if (closed.get()) {
            try { client.close(); } catch (IOException ignored) { }
            return;
        }
        active.set(client);
        firstConnection.complete(null);
        failedAttempts.set(0);
        reconnectScheduled.set(false);
        client.termination().thenAccept(reason -> {
            if (active.compareAndSet(client, null) && !closed.get()) scheduleReconnect();
        });
    }

    private void scheduleReconnect() {
        if (!reconnectScheduled.compareAndSet(false, true) || closed.get()) return;
        int attempt = failedAttempts.getAndIncrement();
        long base = policy.initialDelay.toMillis();
        long cap = policy.maxDelay.toMillis();
        long exponential = base * (1L << Math.min(attempt, 20));
        long bounded = Math.min(cap, exponential < 0 ? cap : exponential);
        double jitter = ThreadLocalRandom.current().nextDouble(0.8, 1.2);
        long delay = Math.max(1, (long) (bounded * jitter));
        scheduler.schedule(this::tryReconnect, delay, TimeUnit.MILLISECONDS);
    }

    private void tryReconnect() {
        reconnectScheduled.set(false);
        if (closed.get() || active.get() != null) return;
        try { install(MoonLightClient.connect(endpoint, performance, telemetry)); }
        catch (IOException | RuntimeException error) { scheduleReconnect(); }
    }

    private static MoonLightPerformanceOptions automaticPerformance(String endpoint) throws IOException {
        try {
            return MoonLightPerformanceOptions.automatic(java.net.URI.create(endpoint).getScheme());
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid MoonLightBridge endpoint: " + endpoint, error);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        scheduler.shutdownNow();
        firstConnection.completeExceptionally(new BackendUnavailableException(endpoint));
        MoonLightClient client = active.getAndSet(null);
        if (client != null) client.close();
    }

    public record ReconnectPolicy(Duration initialDelay, Duration maxDelay) {
        public ReconnectPolicy {
            Objects.requireNonNull(initialDelay);
            Objects.requireNonNull(maxDelay);
            if (initialDelay.isZero() || initialDelay.isNegative()) throw new IllegalArgumentException("initialDelay must be positive");
            if (maxDelay.compareTo(initialDelay) < 0) throw new IllegalArgumentException("maxDelay must not be less than initialDelay");
        }

        public static ReconnectPolicy defaults() {
            return new ReconnectPolicy(Duration.ofMillis(100), Duration.ofSeconds(5));
        }
    }

    public static final class BackendUnavailableException extends IOException {
        private static final long serialVersionUID = 1L;

        public BackendUnavailableException(String endpoint) {
            super("MoonLightBridge backend is disconnected: " + endpoint);
        }
    }
}
