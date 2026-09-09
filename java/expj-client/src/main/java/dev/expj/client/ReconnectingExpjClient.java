package dev.expj.client;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ReconnectingExpjClient implements ExpjChannel {
    private final String endpoint;
    private final ReconnectPolicy policy;
    private final ExpjPerformanceOptions performance;
    private final ExpjTelemetry telemetry;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<ExpjClient> active = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger failedAttempts = new AtomicInteger();

    private ReconnectingExpjClient(
        String endpoint,
        ReconnectPolicy policy,
        ExpjPerformanceOptions performance,
        ExpjTelemetry telemetry
    ) throws IOException {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.policy = Objects.requireNonNull(policy);
        this.performance = Objects.requireNonNull(performance);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("expj-reconnect").factory());
        install(ExpjClient.connect(endpoint, performance, telemetry));
    }

    public static ReconnectingExpjClient connect(String endpoint) throws IOException {
        return new ReconnectingExpjClient(
            endpoint, ReconnectPolicy.defaults(), automaticPerformance(endpoint), ExpjTelemetry.automatic());
    }

    public static ReconnectingExpjClient connect(String endpoint, ReconnectPolicy policy) throws IOException {
        return new ReconnectingExpjClient(
            endpoint, policy, automaticPerformance(endpoint), ExpjTelemetry.automatic());
    }

    public static ReconnectingExpjClient connect(
        String endpoint,
        ReconnectPolicy policy,
        ExpjPerformanceOptions performance,
        ExpjTelemetry telemetry
    ) throws IOException {
        return new ReconnectingExpjClient(endpoint, policy, performance, telemetry);
    }

    public boolean isConnected() { return active.get() != null; }

    public CompletableFuture<byte[]> request(int methodId, byte[] body, Duration deadline) {
        ExpjClient client = active.get();
        if (client == null) {
            return CompletableFuture.failedFuture(new BackendUnavailableException(endpoint));
        }
        return client.request(methodId, body, deadline);
    }

    public CompletableFuture<Void> ping(Duration timeout) {
        ExpjClient client = active.get();
        if (client == null) {
            return CompletableFuture.failedFuture(new BackendUnavailableException(endpoint));
        }
        return client.ping(timeout);
    }

    private void install(ExpjClient client) {
        if (closed.get()) {
            try { client.close(); } catch (IOException ignored) { }
            return;
        }
        active.set(client);
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
        try { install(ExpjClient.connect(endpoint, performance, telemetry)); }
        catch (IOException | RuntimeException error) { scheduleReconnect(); }
    }

    private static ExpjPerformanceOptions automaticPerformance(String endpoint) throws IOException {
        try {
            return ExpjPerformanceOptions.automatic(java.net.URI.create(endpoint).getScheme());
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid EXPJ endpoint: " + endpoint, error);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        scheduler.shutdownNow();
        ExpjClient client = active.getAndSet(null);
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
        public BackendUnavailableException(String endpoint) {
            super("EXPJ backend is disconnected: " + endpoint);
        }
    }
}
