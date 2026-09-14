package ru.moonlightproject.bridge.paper;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;
import ru.moonlightproject.bridge.client.MoonLightChannel;
import ru.moonlightproject.bridge.client.MoonLightHealth;

/** Embedded Paper/Folia facade. This is a library object, not a server plugin. */
public final class MoonLightBridge implements AutoCloseable {
    private final Plugin plugin;
    private final ru.moonlightproject.bridge.MoonLightBridge runtime;
    private final AtomicBoolean closed = new AtomicBoolean();

    private MoonLightBridge(Plugin plugin, ru.moonlightproject.bridge.MoonLightBridge runtime) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * Starts connection supervision without blocking the server thread.
     *
     * @param plugin plugin that owns callbacks and the bridge lifecycle
     * @param endpoint {@code tcp://}, {@code tls://}, or {@code unix:} backend endpoint
     * @return non-blocking Paper/Folia bridge facade
     * @throws IOException when the endpoint configuration is invalid
     */
    public static MoonLightBridge start(Plugin plugin, String endpoint) throws IOException {
        return new MoonLightBridge(plugin, ru.moonlightproject.bridge.MoonLightBridge.start(endpoint));
    }

    /**
     * Returns the channel accepted by generated clients and event helpers.
     *
     * @return supervised universal channel
     */
    public MoonLightChannel channel() { return runtime; }

    /**
     * Returns whether the backend connection is currently ready.
     *
     * @return {@code true} while calls can be accepted
     */
    public boolean isConnected() { return runtime.isConnected(); }

    /**
     * Returns whether this plugin-owned facade has been closed.
     *
     * @return {@code true} after the first call to {@link #close()}
     */
    public boolean isClosed() { return closed.get(); }
    /**
     * Returns the latest connection failure, or {@code null}.
     *
     * @return latest supervision failure, or {@code null}
     */
    public Throwable lastFailure() { return runtime.lastFailure(); }
    /**
     * Returns the number of calls waiting for a response on the active connection.
     *
     * @return current in-flight request count
     */
    public int pendingRequests() { return runtime.pendingRequests(); }
    /**
     * Requests a framework-level backend health snapshot.
     *
     * @param timeout maximum time to wait for the snapshot
     * @return future containing server readiness and load counters
     */
    public CompletableFuture<MoonLightHealth> health(Duration timeout) { return runtime.health(timeout); }

    /**
     * Completes after the first successful backend handshake.
     *
     * @return shared initial-readiness stage
     */
    public CompletionStage<Void> firstConnection() { return runtime.firstConnection(); }

    /**
     * Wraps an asynchronous result for scheduler-safe Paper/Folia completion dispatch.
     *
     * @param request asynchronous bridge or application result
     * @param <T> result type
     * @return scheduler-aware result wrapper
     */
    public <T> PaperCall<T> call(CompletionStage<T> request) {
        return new PaperCall<>(request, plugin, this::callbacksAvailable);
    }

    /**
     * Runs codec/JIT warm-up after the first connection, away from Minecraft threads.
     *
     * @param operation asynchronous operation used for warm-up
     * @param <T> result type
     * @return scheduler-aware warm-up result
     */
    public <T> PaperCall<T> warmUp(Supplier<? extends CompletionStage<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        return call(runtime.warmUp(operation));
    }

    private boolean callbacksAvailable() {
        return !closed.get() && plugin.isEnabled();
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        runtime.close();
    }
}
