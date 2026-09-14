package ru.moonlightproject.bridge;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import ru.moonlightproject.bridge.client.MoonLightChannel;
import ru.moonlightproject.bridge.client.MoonLightHealth;
import ru.moonlightproject.bridge.client.ReconnectingMoonLightClient;

/**
 * Universal Java lifecycle facade for a supervised MoonLightBridge connection.
 *
 * <p>{@link #start(String)} never performs network I/O on the calling thread. Requests fail fast
 * while disconnected, and newly submitted requests use the connection restored by the supervisor.
 * Interrupted requests are never replayed automatically.</p>
 */
public final class MoonLightBridge implements MoonLightChannel {
    private final ReconnectingMoonLightClient client;
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();

    private MoonLightBridge(ReconnectingMoonLightClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Starts connection supervision without blocking the calling thread on network I/O.
     *
     * @param endpoint a {@code tcp://}, {@code tls://}, or {@code unix:} endpoint
     * @return a supervised universal Java bridge
     * @throws IOException when the endpoint configuration is invalid
     */
    public static MoonLightBridge start(String endpoint) throws IOException {
        return new MoonLightBridge(ReconnectingMoonLightClient.start(endpoint));
    }

    /**
     * Establishes the first connection before returning and then supervises reconnects.
     *
     * @param endpoint a {@code tcp://}, {@code tls://}, or {@code unix:} endpoint
     * @return a connected universal Java bridge
     * @throws IOException when the first connection cannot be established
     */
    public static MoonLightBridge connect(String endpoint) throws IOException {
        return new MoonLightBridge(ReconnectingMoonLightClient.connect(endpoint));
    }

    /** Returns this instance as the channel accepted by generated clients. */
    public MoonLightChannel channel() {
        return this;
    }

    /** Returns whether a physical connection is currently ready. */
    public boolean isConnected() {
        return client.isConnected();
    }

    /** Returns whether this lifecycle facade has been closed. */
    public boolean isClosed() {
        return closed.get();
    }

    /** Returns the most recent connection failure, or {@code null}. */
    public Throwable lastFailure() {
        return client.lastFailure();
    }

    /** Returns the number of requests waiting for a response on the active connection. */
    public int pendingRequests() {
        return client.pendingRequests();
    }

    /** Completes once, after the first successful connection. */
    public CompletionStage<Void> firstConnection() {
        return client.firstConnection();
    }

    /**
     * Runs codec/JIT warm-up after the first connection on a virtual thread.
     *
     * @param operation asynchronous operation used for warm-up
     * @param <T> result type
     * @return the warm-up result
     */
    public <T> CompletableFuture<T> warmUp(Supplier<? extends CompletionStage<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        return client.firstConnection().thenComposeAsync(ignored -> {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("bridge is closed"));
            }
            try {
                return operation.get();
            } catch (RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }
        }, background).toCompletableFuture();
    }

    @Override
    public CompletableFuture<byte[]> request(int methodId, byte[] body, Duration deadline) {
        return client.request(methodId, body, deadline);
    }

    @Override
    public CompletableFuture<Void> ping(Duration timeout) {
        return client.ping(timeout);
    }

    @Override
    public CompletableFuture<MoonLightHealth> health(Duration timeout) {
        return client.health(timeout);
    }

    @Override
    public AutoCloseable subscribe(int eventId, Consumer<byte[]> listener) {
        return client.subscribe(eventId, listener);
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        background.shutdownNow();
        client.close();
    }
}
