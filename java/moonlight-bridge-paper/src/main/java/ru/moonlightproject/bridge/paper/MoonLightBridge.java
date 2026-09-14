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

    /** Starts connection supervision without blocking the server thread. */
    public static MoonLightBridge start(Plugin plugin, String endpoint) throws IOException {
        return new MoonLightBridge(plugin, ru.moonlightproject.bridge.MoonLightBridge.start(endpoint));
    }

    /** Returns the channel accepted by generated clients and event helpers. */
    public MoonLightChannel channel() { return runtime; }

    /** Returns whether the backend connection is currently ready. */
    public boolean isConnected() { return runtime.isConnected(); }

    /** Returns whether this plugin-owned facade has been closed. */
    public boolean isClosed() { return closed.get(); }
    /** Returns the latest connection failure, or {@code null}. */
    public Throwable lastFailure() { return runtime.lastFailure(); }
    /** Returns the number of calls waiting for a response on the active connection. */
    public int pendingRequests() { return runtime.pendingRequests(); }
    /** Requests a framework-level backend health snapshot. */
    public CompletableFuture<MoonLightHealth> health(Duration timeout) { return runtime.health(timeout); }

    /** Completes after the first successful backend handshake. */
    public CompletionStage<Void> firstConnection() { return runtime.firstConnection(); }

    /** Wraps an asynchronous result for scheduler-safe Paper/Folia completion dispatch. */
    public <T> PaperCall<T> call(CompletionStage<T> request) {
        return new PaperCall<>(request, plugin, this::callbacksAvailable);
    }

    /** Runs codec/JIT warm-up after the first connection, away from Minecraft threads. */
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
