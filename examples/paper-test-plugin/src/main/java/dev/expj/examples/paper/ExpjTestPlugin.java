package dev.expj.examples.paper;

import dev.expj.client.ReconnectingExpjClient;
import dev.expj.example.v1.EchoRequest;
import dev.expj.example.v1.EchoServiceClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExpjTestPlugin extends JavaPlugin implements CommandExecutor {
    private final AtomicReference<ReconnectingExpjClient> connection = new AtomicReference<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile EchoServiceClient backend;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("expjtest").setExecutor(this);
        getCommand("expjbatch").setExecutor(this);

        String endpoint = getConfig().getString("endpoint", "tcp://127.0.0.1:38201");
        Thread.ofVirtual().name("expj-test-connect").start(() -> connect(endpoint));
    }

    private void connect(String endpoint) {
        while (!stopping.get()) {
            try {
                ReconnectingExpjClient client = ReconnectingExpjClient.connect(endpoint);
                if (stopping.get()) {
                    client.close();
                    return;
                }
                EchoServiceClient service = new EchoServiceClient(client);
                try {
                    // Load and JIT the generated Protobuf codec, telemetry, and
                    // complete RPC path away from the Minecraft server thread.
                    service.echo(EchoRequest.newBuilder().setMessage("EXPJ warmup").build()).join();
                } catch (CompletionException error) {
                    client.close();
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    throw new IOException("EXPJ warmup request failed", cause);
                }
                connection.set(client);
                backend = service;
                getLogger().info("Connected to and warmed EXPJ backend at " + endpoint);
                return;
            } catch (IOException error) {
                if (!stopping.get()) {
                    getLogger().warning(
                        "EXPJ backend is unavailable; retrying in 2 seconds: " + error.getMessage());
                }
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        EchoServiceClient client = backend;
        if (client == null || connection.get() == null || !connection.get().isConnected()) {
            sender.sendMessage(Component.text("EXPJ backend is not connected", NamedTextColor.RED));
            return true;
        }
        if (command.getName().equalsIgnoreCase("expjtest")) {
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
        client.echo(EchoRequest.newBuilder().setMessage(text).build())
            .whenComplete((response, error) -> {
                long micros = (System.nanoTime() - started) / 1_000;
                onMainThread(() -> {
                    if (error != null) {
                        showError(sender, error);
                        return;
                    }
                    sender.sendMessage(Component.text(response.getMessage(), NamedTextColor.GREEN)
                        .append(Component.text(" (RPC " + micros + " µs)", NamedTextColor.GRAY)));
                });
            });
    }

    private void callBatch(CommandSender sender, EchoServiceClient client, int count, String text) {
        List<EchoRequest> requests = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            requests.add(EchoRequest.newBuilder().setMessage(text + " #" + index).build());
        }
        long started = System.nanoTime();
        client.echoBatch(requests).whenComplete((responses, error) -> {
            long micros = (System.nanoTime() - started) / 1_000;
            onMainThread(() -> {
                if (error != null) {
                    showError(sender, error);
                    return;
                }
                sender.sendMessage(Component.text(
                    "Rust returned " + responses.size() + " responses in " + micros + " µs RPC time",
                    NamedTextColor.GREEN));
            });
        });
    }

    private void showError(CommandSender sender, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
            ? error.getCause() : error;
        sender.sendMessage(Component.text(
            "EXPJ request failed: " + cause.getMessage(), NamedTextColor.RED));
    }

    private void onMainThread(Runnable action) {
        if (!stopping.get()) getServer().getScheduler().runTask(this, action);
    }

    @Override
    public void onDisable() {
        stopping.set(true);
        backend = null;
        ReconnectingExpjClient client = connection.getAndSet(null);
        if (client != null) {
            try {
                client.close();
            } catch (IOException error) {
                getLogger().warning("Failed to close EXPJ cleanly: " + error.getMessage());
            }
        }
    }
}
