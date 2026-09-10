package ru.moonlightproject.bridge.examples.paper.load;

import ru.moonlightproject.bridge.example.v1.EchoRequest;
import ru.moonlightproject.bridge.example.v1.EchoServiceClient;
import ru.moonlightproject.bridge.paper.MoonLightBridge;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class MoonLightLoadTestPlugin extends JavaPlugin implements CommandExecutor {
    private static final int MAX_REQUESTS = 1_000_000;
    private static final int MAX_CONCURRENCY = 256;
    private static final int MAX_PAYLOAD_BYTES = 65_536;

    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService coordinator = Executors.newVirtualThreadPerTaskExecutor();
    private MoonLightBridge bridge;
    private EchoServiceClient backend;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("moonlightload"), "moonlightload command is not registered")
            .setExecutor(this);
        String endpoint = getConfig().getString("endpoint", "tcp://127.0.0.1:38201");
        try {
            bridge = MoonLightBridge.start(this, endpoint);
            backend = new EchoServiceClient(bridge.channel());
        } catch (IOException error) {
            getLogger().severe("Invalid MoonLightBridge endpoint: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public boolean onCommand(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String label,
        @NotNull String[] args
    ) {
        if (bridge == null || !bridge.isConnected()) {
            sender.sendMessage(Component.text("MoonLightBridge backend is not connected", NamedTextColor.RED));
            return true;
        }

        LoadOptions options;
        try {
            options = parseOptions(args);
        } catch (IllegalArgumentException error) {
            sender.sendMessage(Component.text(error.getMessage(), NamedTextColor.RED));
            return true;
        }
        if (!running.compareAndSet(false, true)) {
            sender.sendMessage(Component.text("An MoonLightBridge load test is already running", NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(Component.text(
            "Starting MoonLightBridge load test: " + options.requests() + " requests, concurrency "
                + options.concurrency() + ", payload " + options.payloadBytes() + " bytes",
            NamedTextColor.YELLOW));
        CompletableFuture<LoadResult> result = CompletableFuture.supplyAsync(
            () -> runLoad(options), coordinator);
        result.whenComplete((ignored, error) -> running.set(false));
        bridge.call(result).whenCompleteFor(sender, (summary, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
                sender.sendMessage(Component.text("Load test failed: " + cause.getMessage(), NamedTextColor.RED));
                return;
            }
            sender.sendMessage(Component.text(summary.format(), NamedTextColor.GREEN));
        });
        return true;
    }

    private LoadResult runLoad(LoadOptions options) {
        EchoRequest request = EchoRequest.newBuilder()
            .setMessage("a".repeat(options.payloadBytes()))
            .build();
        int warmupRequests = Math.min(512, Math.max(32, options.concurrency() * 2));
        PhaseResult warmup = runPhase(warmupRequests, options.concurrency(), request, false);
        if (warmup.failures() != 0) {
            throw new CompletionException(warmup.firstError());
        }
        return runPhase(options.requests(), options.concurrency(), request, true).summarize();
    }

    private PhaseResult runPhase(int requests, int concurrency, EchoRequest request, boolean measure) {
        long[] latency = measure ? new long[requests] : null;
        AtomicInteger cursor = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        CompletableFuture<?>[] workers = new CompletableFuture<?>[concurrency];
        long phaseStarted = System.nanoTime();
        for (int worker = 0; worker < workers.length; worker++) {
            workers[worker] = CompletableFuture.runAsync(() -> {
                int index;
                while ((index = cursor.getAndIncrement()) < requests) {
                    long started = System.nanoTime();
                    try {
                        backend.echo(request).join();
                        if (measure) latency[index] = System.nanoTime() - started;
                    } catch (CompletionException error) {
                        failures.incrementAndGet();
                        firstError.compareAndSet(null, error.getCause() == null ? error : error.getCause());
                    }
                }
            }, coordinator);
        }
        CompletableFuture.allOf(workers).join();
        return new PhaseResult(
            latency,
            System.nanoTime() - phaseStarted,
            failures.get(),
            firstError.get());
    }

    private static LoadOptions parseOptions(String[] args) {
        int requests = integerArg(args, 0, 10_000, 1, MAX_REQUESTS, "requests");
        int concurrency = integerArg(args, 1, 128, 1, MAX_CONCURRENCY, "concurrency");
        int payloadBytes = integerArg(args, 2, 64, 0, MAX_PAYLOAD_BYTES, "payload-bytes");
        return new LoadOptions(requests, concurrency, payloadBytes);
    }

    private static int integerArg(
        String[] args, int index, int fallback, int minimum, int maximum, String name
    ) {
        if (args.length <= index) return fallback;
        try {
            int value = Integer.parseInt(args[index]);
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be from " + minimum + " to " + maximum);
        }
    }

    @Override
    public void onDisable() {
        coordinator.shutdownNow();
        if (bridge == null) return;
        try {
            bridge.close();
        } catch (IOException error) {
            getLogger().warning("Failed to close MoonLightBridge cleanly: " + error.getMessage());
        }
    }

    private record LoadOptions(int requests, int concurrency, int payloadBytes) { }

    private record PhaseResult(
        long[] latency,
        long elapsedNanos,
        int failures,
        Throwable firstError
    ) {
        LoadResult summarize() {
            long[] successful = Arrays.stream(latency).filter(value -> value > 0).sorted().toArray();
            if (successful.length == 0) {
                throw new CompletionException(firstError == null
                    ? new IOException("every load-test request failed") : firstError);
            }
            double throughput = latency.length / (elapsedNanos / 1_000_000_000.0);
            return new LoadResult(
                latency.length,
                failures,
                throughput,
                percentile(successful, 50),
                percentile(successful, 95),
                percentile(successful, 99),
                successful[successful.length - 1]);
        }

        private static long percentile(long[] sorted, int percentile) {
            int index = (int) Math.ceil(sorted.length * percentile / 100.0) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
        }
    }

    private record LoadResult(
        int requests,
        int failures,
        double requestsPerSecond,
        long p50Nanos,
        long p95Nanos,
        long p99Nanos,
        long maxNanos
    ) {
        String format() {
            return String.format(
                "MoonLightBridge load: %,d requests, %,.0f req/s, p50 %.1f µs, p95 %.1f µs, "
                    + "p99 %.1f µs, max %.1f µs, failures %,d",
                requests,
                requestsPerSecond,
                p50Nanos / 1_000.0,
                p95Nanos / 1_000.0,
                p99Nanos / 1_000.0,
                maxNanos / 1_000.0,
                failures);
        }
    }
}
