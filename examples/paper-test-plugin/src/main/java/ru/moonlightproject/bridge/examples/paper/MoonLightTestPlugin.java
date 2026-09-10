package ru.moonlightproject.bridge.examples.paper;

import ru.moonlightproject.bridge.example.v1.EchoRequest;
import ru.moonlightproject.bridge.example.v1.EchoServiceClient;
import ru.moonlightproject.bridge.paper.MoonLightBridge;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class MoonLightTestPlugin extends JavaPlugin implements CommandExecutor {
    private MoonLightBridge bridge;
    private volatile EchoServiceClient backend;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("moonlighttest"), "moonlighttest command is not registered")
            .setExecutor(this);
        Objects.requireNonNull(getCommand("moonlightbatch"), "moonlightbatch command is not registered")
            .setExecutor(this);

        String endpoint = getConfig().getString("endpoint", "tcp://127.0.0.1:38201");
        try {
            bridge = MoonLightBridge.start(this, endpoint);
        } catch (IOException error) {
            getLogger().severe("Invalid MoonLightBridge endpoint: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        backend = new EchoServiceClient(bridge.channel());
        bridge.warmUp(() -> backend.echo(request("MoonLightBridge warmup")))
            .whenCompleteOnGlobal((ignored, error) -> {
                if (error == null) {
                    getLogger().info("Connected to and warmed MoonLightBridge backend at " + endpoint);
                } else {
                    getLogger().warning("MoonLightBridge warm-up failed: " + error.getMessage());
                }
            });
    }

    @Override
    public boolean onCommand(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String label,
        @NotNull String[] args
    ) {
        EchoServiceClient client = backend;
        MoonLightBridge activeBridge = bridge;
        if (client == null || activeBridge == null || !activeBridge.isConnected()) {
            sender.sendMessage(Component.text("MoonLightBridge backend is not connected", NamedTextColor.RED));
            return true;
        }
        if (command.getName().equalsIgnoreCase("moonlighttest")) {
            String text = args.length == 0 ? "Hello from Paper" : String.join(" ", args);
            callOnce(sender, client, text);
            return true;
        }
        int count;
        try {
            count = args.length == 0 ? 32 : Math.max(1, Math.min(256, Integer.parseInt(args[0])));
        } catch (NumberFormatException error) {
            sender.sendMessage(Component.text("Count must be a number from 1 to 256", NamedTextColor.RED));
            return true;
        }
        String text = args.length < 2
            ? "Paper batch"
            : String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        callBatch(sender, client, count, text);
        return true;
    }

    private void callOnce(CommandSender sender, EchoServiceClient client, String text) {
        long started = System.nanoTime();
        bridge.call(client.echo(request(text))
                .thenApply(response -> timed(response, started)))
            .whenCompleteFor(sender, (result, error) -> {
                if (error != null) {
                    showError(sender, error);
                    return;
                }
                long callbackMicros = (System.nanoTime() - result.completedAtNanos()) / 1_000;
                sender.sendMessage(Component.text(result.value().getMessage(), NamedTextColor.GREEN)
                    .append(Component.text(
                        " (RPC " + result.rpcMicros() + " µs, callback " + callbackMicros + " µs)",
                        NamedTextColor.GRAY)));
            });
    }

    private void callBatch(CommandSender sender, EchoServiceClient client, int count, String text) {
        List<EchoRequest> requests = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            requests.add(EchoRequest.newBuilder().setMessage(text + " #" + index).build());
        }
        long started = System.nanoTime();
        bridge.call(client.echoBatch(requests)
                .thenApply(responses -> timed(responses, started)))
            .whenCompleteFor(sender, (result, error) -> {
            if (error != null) {
                showError(sender, error);
                return;
            }
            long callbackMicros = (System.nanoTime() - result.completedAtNanos()) / 1_000;
            sender.sendMessage(Component.text(
                "Rust returned " + result.value().size() + " responses in " + result.rpcMicros()
                    + " µs RPC time; callback " + callbackMicros + " µs",
                NamedTextColor.GREEN));
        });
    }

    private static <T> Timed<T> timed(T value, long startedNanos) {
        long completed = System.nanoTime();
        return new Timed<>(value, (completed - startedNanos) / 1_000, completed);
    }

    private static EchoRequest request(String message) {
        return EchoRequest.newBuilder().setMessage(message).build();
    }

    private record Timed<T>(T value, long rpcMicros, long completedAtNanos) { }

    private void showError(CommandSender sender, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
            ? error.getCause() : error;
        sender.sendMessage(Component.text(
            "MoonLightBridge request failed: " + cause.getMessage(), NamedTextColor.RED));
    }

    @Override
    public void onDisable() {
        backend = null;
        MoonLightBridge activeBridge = bridge;
        bridge = null;
        if (activeBridge != null) {
            try {
                activeBridge.close();
            } catch (IOException error) {
                getLogger().warning("Failed to close MoonLightBridge cleanly: " + error.getMessage());
            }
        }
    }
}
