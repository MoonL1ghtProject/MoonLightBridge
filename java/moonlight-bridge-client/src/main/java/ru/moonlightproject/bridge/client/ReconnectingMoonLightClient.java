package ru.moonlightproject.bridge.client;

import java.io.IOException;
import java.io.Serial;
import java.time.Duration;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Low-level connection supervisor that reconnects without replaying interrupted requests. */
public final class ReconnectingMoonLightClient implements MoonLightChannel {
    private final String endpoint;
    private final ReconnectPolicy policy;
    private final MoonLightPerformanceOptions performance;
    private final MoonLightTelemetry telemetry;
    private final List<MoonLightInterceptor> interceptors;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<MoonLightClient> active = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger failedAttempts = new AtomicInteger();
    private final CompletableFuture<Void> firstConnection = new CompletableFuture<>();
    private final ConcurrentMap<Integer, CopyOnWriteArrayList<Consumer<byte[]>>> eventListeners =
        new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();

    private ReconnectingMoonLightClient(
        String endpoint,
        ReconnectPolicy policy,
        MoonLightPerformanceOptions performance,
        MoonLightTelemetry telemetry,
        List<? extends MoonLightInterceptor> interceptors,
        boolean connectImmediately
    ) throws IOException {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.policy = Objects.requireNonNull(policy);
        this.performance = Objects.requireNonNull(performance);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.interceptors = List.copyOf(interceptors);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("moonlight-bridge-reconnect").factory());
        if (connectImmediately) {
            install(MoonLightClient.connect(endpoint, performance, telemetry, this.interceptors));
        } else {
            reconnectScheduled.set(true);
            scheduler.execute(this::tryReconnect);
        }
    }

    /**
     * Connects immediately using default reconnect and transport settings.
     *
     * @param endpoint complete transport endpoint
     * @return client after its first successful handshake
     * @throws IOException when the first connection cannot be established
     */
    public static ReconnectingMoonLightClient connect(String endpoint) throws IOException {
        return new ReconnectingMoonLightClient(
            endpoint, ReconnectPolicy.defaults(), automaticPerformance(endpoint), MoonLightTelemetry.automatic(), List.of(), true);
    }

    /**
     * Connects immediately using the supplied reconnect policy.
     *
     * @param endpoint complete transport endpoint
     * @param policy reconnect backoff policy
     * @return client after its first successful handshake
     * @throws IOException when the first connection cannot be established
     */
    public static ReconnectingMoonLightClient connect(String endpoint, ReconnectPolicy policy) throws IOException {
        return new ReconnectingMoonLightClient(
            endpoint, policy, automaticPerformance(endpoint), MoonLightTelemetry.automatic(), List.of(), true);
    }

    /**
     * Connects immediately using explicit reconnect, performance, and instrumentation settings.
     *
     * @param endpoint complete transport endpoint
     * @param policy reconnect backoff policy
     * @param performance writer, batching, and buffer settings
     * @param telemetry local instrumentation implementation
     * @return client after its first successful handshake
     * @throws IOException when the first connection cannot be established
     */
    public static ReconnectingMoonLightClient connect(
        String endpoint,
        ReconnectPolicy policy,
        MoonLightPerformanceOptions performance,
        MoonLightTelemetry telemetry
    ) throws IOException {
        return new ReconnectingMoonLightClient(endpoint, policy, performance, telemetry, List.of(), true);
    }

    /**
     * Connects immediately with an immutable interceptor chain retained across reconnects.
     *
     * @param endpoint complete transport endpoint
     * @param policy reconnect backoff policy
     * @param performance writer, batching, and buffer settings
     * @param telemetry local instrumentation implementation
     * @param interceptors call interceptors in registration order
     * @return supervised client after its first successful handshake
     * @throws IOException when the first connection cannot be established
     */
    public static ReconnectingMoonLightClient connect(
        String endpoint,
        ReconnectPolicy policy,
        MoonLightPerformanceOptions performance,
        MoonLightTelemetry telemetry,
        List<? extends MoonLightInterceptor> interceptors
    ) throws IOException {
        return new ReconnectingMoonLightClient(
            endpoint, policy, performance, telemetry, interceptors, true);
    }

    /**
     * Starts a supervised client without performing network I/O on the calling thread.
     * Requests fail fast until the first connection is established.
     *
     * @param endpoint complete transport endpoint
     * @return client whose supervisor connects in the background
     * @throws IOException when the endpoint configuration is invalid
     */
    public static ReconnectingMoonLightClient start(String endpoint) throws IOException {
        return new ReconnectingMoonLightClient(
            endpoint, ReconnectPolicy.defaults(), automaticPerformance(endpoint), deferredAutomaticTelemetry(), List.of(), false);
    }

    /**
     * Starts asynchronous connection supervision using the supplied reconnect policy.
     *
     * @param endpoint complete transport endpoint
     * @param policy reconnect backoff policy
     * @return client whose supervisor connects in the background
     * @throws IOException when the endpoint configuration is invalid
     */
    public static ReconnectingMoonLightClient start(String endpoint, ReconnectPolicy policy) throws IOException {
        return new ReconnectingMoonLightClient(
            endpoint, policy, automaticPerformance(endpoint), deferredAutomaticTelemetry(), List.of(), false);
    }

    private static MoonLightTelemetry deferredAutomaticTelemetry() {
        return new MoonLightTelemetry() {
            private final AtomicReference<MoonLightTelemetry> delegate = new AtomicReference<>();

            private MoonLightTelemetry delegate() {
                MoonLightTelemetry current = delegate.get();
                if (current != null) return current;
                MoonLightTelemetry loaded = MoonLightTelemetry.automatic();
                return delegate.compareAndSet(null, loaded) ? loaded : delegate.get();
            }

            @Override public RequestObservation startRequest(RequestInfo request) {
                return delegate().startRequest(request);
            }
            @Override public FunctionObservation startFunction(String name) {
                return delegate().startFunction(name);
            }
            @Override public void connectionOpened() { delegate().connectionOpened(); }
            @Override public void connectionClosed(Throwable cause) { delegate().connectionClosed(cause); }
        };
    }

    /**
     * Starts asynchronous supervision using explicit reconnect, performance, and instrumentation settings.
     *
     * @param endpoint complete transport endpoint
     * @param policy reconnect backoff policy
     * @param performance writer, batching, and buffer settings
     * @param telemetry local instrumentation implementation
     * @return client whose supervisor connects in the background
     * @throws IOException when the endpoint configuration is invalid
     */
    public static ReconnectingMoonLightClient start(
        String endpoint,
        ReconnectPolicy policy,
        MoonLightPerformanceOptions performance,
        MoonLightTelemetry telemetry
    ) throws IOException {
        return new ReconnectingMoonLightClient(endpoint, policy, performance, telemetry, List.of(), false);
    }

    /**
     * Starts asynchronous supervision with an interceptor chain retained across reconnects.
     *
     * @param endpoint complete transport endpoint
     * @param policy reconnect backoff policy
     * @param performance writer, batching, and buffer settings
     * @param telemetry local instrumentation implementation
     * @param interceptors call interceptors in registration order
     * @return client whose supervisor connects in the background
     * @throws IOException when the endpoint configuration is invalid
     */
    public static ReconnectingMoonLightClient start(
        String endpoint,
        ReconnectPolicy policy,
        MoonLightPerformanceOptions performance,
        MoonLightTelemetry telemetry,
        List<? extends MoonLightInterceptor> interceptors
    ) throws IOException {
        return new ReconnectingMoonLightClient(
            endpoint, policy, performance, telemetry, interceptors, false);
    }

    /** Returns whether a physical connection is currently installed.
     * @return {@code true} while requests can be accepted
     */
    public boolean isConnected() { return active.get() != null; }
    /** Returns the most recent connection failure, or {@code null}.
     * @return latest supervision failure, or {@code null}
     */
    public Throwable lastFailure() { return lastFailure.get(); }
    /** Returns requests waiting on the active connection, or zero while disconnected.
     * @return current in-flight request count
     */
    public int pendingRequests() {
        MoonLightClient client = active.get();
        return client == null ? 0 : client.pendingRequests();
    }

    /** Completes once after the first successful connection.
     * @return shared initial-readiness stage
     */
    public CompletionStage<Void> firstConnection() { return firstConnection; }

    public CompletableFuture<byte[]> request(int methodId, byte[] body, Duration deadline) {
        MoonLightClient client = active.get();
        if (client == null) {
            return CompletableFuture.failedFuture(new BackendUnavailableException(endpoint));
        }
        return client.request(methodId, body, deadline);
    }

    @Override
    public CompletableFuture<byte[]> request(
        int methodId, byte[] body, Duration deadline, RpcPolicy policy
    ) {
        MoonLightClient client = active.get();
        if (client == null) {
            return CompletableFuture.failedFuture(new BackendUnavailableException(endpoint));
        }
        return client.request(methodId, body, deadline, policy);
    }

    @Override
    public MoonLightServerStream<byte[]> serverStream(int methodId, byte[] body, Duration deadline) {
        MoonLightClient client = active.get();
        if (client == null) throw new CompletionException(new BackendUnavailableException(endpoint));
        return client.serverStream(methodId, body, deadline);
    }

    @Override
    public MoonLightServerStream<byte[]> serverStream(
        int methodId, byte[] body, Duration deadline, RpcPolicy policy
    ) {
        MoonLightClient client = active.get();
        if (client == null) throw new CompletionException(new BackendUnavailableException(endpoint));
        return client.serverStream(methodId, body, deadline, policy);
    }

    public CompletableFuture<Void> ping(Duration timeout) {
        MoonLightClient client = active.get();
        if (client == null) {
            return CompletableFuture.failedFuture(new BackendUnavailableException(endpoint));
        }
        return client.ping(timeout);
    }

    @Override
    public CompletableFuture<MoonLightHealth> health(Duration timeout) {
        MoonLightClient client = active.get();
        if (client == null) return CompletableFuture.failedFuture(new BackendUnavailableException(endpoint));
        return client.health(timeout);
    }

    @Override
    public AutoCloseable subscribe(int eventId, Consumer<byte[]> listener) {
        if (eventId == 0) throw new IllegalArgumentException("eventId must not be zero");
        Consumer<byte[]> checked = Objects.requireNonNull(listener, "listener");
        CopyOnWriteArrayList<Consumer<byte[]>> listeners =
            eventListeners.computeIfAbsent(eventId, ignored -> new CopyOnWriteArrayList<>());
        listeners.addIfAbsent(checked);
        MoonLightClient client = active.get();
        if (client != null) client.subscribe(eventId, checked);
        return () -> {
            listeners.remove(checked);
            if (listeners.isEmpty()) eventListeners.remove(eventId, listeners);
            MoonLightClient current = active.get();
            if (current != null) current.removeEventListener(eventId, checked);
        };
    }

    private void install(MoonLightClient client) {
        if (closed.get()) {
            try { client.close(); } catch (IOException ignored) { }
            return;
        }
        active.set(client);
        eventListeners.forEach((eventId, listeners) ->
            listeners.forEach(listener -> client.subscribe(eventId, listener)));
        firstConnection.complete(null);
        failedAttempts.set(0);
        reconnectScheduled.set(false);
        client.termination().thenAccept(reason -> {
            lastFailure.set(reason);
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
        try { install(MoonLightClient.connect(endpoint, performance, telemetry, interceptors)); }
        catch (IOException | RuntimeException error) {
            lastFailure.set(error);
            scheduleReconnect();
        }
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

    /**
     * Exponential reconnect-backoff bounds; each attempt also receives random jitter.
     *
     * @param initialDelay delay before the first reconnect attempt
     * @param maxDelay upper bound for exponential backoff
     */
    public record ReconnectPolicy(Duration initialDelay, Duration maxDelay) {
        /** Validates positive, ordered reconnect bounds. */
        public ReconnectPolicy {
            Objects.requireNonNull(initialDelay);
            Objects.requireNonNull(maxDelay);
            if (initialDelay.isZero() || initialDelay.isNegative()) throw new IllegalArgumentException("initialDelay must be positive");
            if (maxDelay.compareTo(initialDelay) < 0) throw new IllegalArgumentException("maxDelay must not be less than initialDelay");
        }

        /**
         * Returns a 100 ms initial delay capped at five seconds.
         *
         * @return default reconnect policy
         */
        public static ReconnectPolicy defaults() {
            return new ReconnectPolicy(Duration.ofMillis(100), Duration.ofSeconds(5));
        }
    }

    /** Indicates that no active backend connection could accept a call. */
    public static final class BackendUnavailableException extends IOException {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Creates an exception naming the disconnected endpoint.
         *
         * @param endpoint endpoint that had no active connection
         */
        public BackendUnavailableException(String endpoint) {
            super("MoonLightBridge backend is disconnected: " + endpoint);
        }
    }
}
