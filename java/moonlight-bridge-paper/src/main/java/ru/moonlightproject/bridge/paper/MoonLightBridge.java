package ru.moonlightproject.bridge.paper;

import ru.moonlightproject.bridge.client.MoonLightChannel;
import ru.moonlightproject.bridge.client.ReconnectingMoonLightClient;
import ru.moonlightproject.bridge.client.MoonLightHealth;
import java.time.Duration;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;

/** Embedded Paper/Folia facade. This is a library object, not a server plugin. */
public final class MoonLightBridge implements AutoCloseable {
    private final Plugin plugin;
    private final ReconnectingMoonLightClient client;
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();

    private MoonLightBridge(Plugin plugin, ReconnectingMoonLightClient client) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Starts connection supervision without blocking the server thread. */
    public static MoonLightBridge start(Plugin plugin, String endpoint) throws IOException {
        return new MoonLightBridge(plugin, ReconnectingMoonLightClient.start(endpoint));
    }

    public MoonLightChannel channel() { return client; }

    public boolean isConnected() { return client.isConnected(); }

    public boolean isClosed() { return closed.get(); }
    public Throwable lastFailure() { return client.lastFailure(); }
    public int pendingRequests() { return client.pendingRequests(); }
    public CompletableFuture<MoonLightHealth> health(Duration timeout) { return client.health(timeout); }

    public CompletionStage<Void> firstConnection() { return client.firstConnection(); }

    public <T> PaperCall<T> call(CompletionStage<T> request) {
        return new PaperCall<>(request, plugin, this::callbacksAvailable);
    }

    /** Runs codec/JIT warm-up after the first connection, away from Minecraft threads. */
    public <T> PaperCall<T> warmUp(Supplier<? extends CompletionStage<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> result = client.firstConnection()
            .thenComposeAsync(ignored -> {
                if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("bridge is closed"));
                try {
                    return operation.get();
                } catch (RuntimeException error) {
                    return CompletableFuture.failedFuture(error);
                }
            }, background)
            .toCompletableFuture();
        return call(result);
    }

    private boolean callbacksAvailable() {
        return !closed.get() && plugin.isEnabled();
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        background.shutdownNow();
        client.close();
    }
}
