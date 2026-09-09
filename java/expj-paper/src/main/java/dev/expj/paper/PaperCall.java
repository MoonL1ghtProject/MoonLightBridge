package dev.expj.paper;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** A non-blocking RPC result that can only enter Bukkit state through a scheduler. */
public final class PaperCall<T> {
    private final CompletionStage<T> source;
    private final Plugin plugin;
    private final BooleanSupplier active;

    PaperCall(CompletionStage<T> source, Plugin plugin, BooleanSupplier active) {
        this.source = Objects.requireNonNull(source, "source");
        this.plugin = plugin;
        this.active = Objects.requireNonNull(active, "active");
    }

    public CompletableFuture<Void> thenOnGlobal(Consumer<? super T> action) {
        return whenCompleteOnGlobal(successOnly(action));
    }

    public CompletableFuture<Void> thenAt(Location location, Consumer<? super T> action) {
        return whenCompleteAt(location, successOnly(action));
    }

    public CompletableFuture<Void> thenFor(Entity entity, Consumer<? super T> action) {
        return whenCompleteFor(entity, successOnly(action));
    }

    public CompletableFuture<Void> whenCompleteOnGlobal(BiConsumer<? super T, ? super Throwable> action) {
        return whenComplete(PaperDispatchers.global(requirePlugin()), action);
    }

    public CompletableFuture<Void> whenCompleteAt(
        Location location,
        BiConsumer<? super T, ? super Throwable> action
    ) {
        return whenComplete(PaperDispatchers.at(requirePlugin(), location), action);
    }

    public CompletableFuture<Void> whenCompleteFor(
        Entity entity,
        BiConsumer<? super T, ? super Throwable> action
    ) {
        return whenComplete(PaperDispatchers.forEntity(requirePlugin(), entity), action);
    }

    public CompletableFuture<Void> whenCompleteFor(
        CommandSender sender,
        BiConsumer<? super T, ? super Throwable> action
    ) {
        return whenComplete(PaperDispatchers.forSender(requirePlugin(), sender), action);
    }

    public CompletableFuture<Void> whenComplete(
        PaperDispatcher dispatcher,
        BiConsumer<? super T, ? super Throwable> action
    ) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(action, "action");
        CompletableFuture<Void> scheduled = new CompletableFuture<>();
        source.whenComplete((value, error) -> {
            if (!active.getAsBoolean()) {
                scheduled.completeExceptionally(new CallbackUnavailableException());
                return;
            }
            Runnable unavailable = () ->
                scheduled.completeExceptionally(new CallbackUnavailableException());
            dispatcher.dispatch(() -> {
                if (!active.getAsBoolean()) {
                    unavailable.run();
                    return;
                }
                try {
                    action.accept(value, unwrap(error));
                    scheduled.complete(null);
                } catch (Throwable callbackError) {
                    scheduled.completeExceptionally(callbackError);
                }
            }, unavailable);
        });
        return scheduled;
    }

    private Plugin requirePlugin() {
        if (plugin == null) throw new IllegalStateException("this call has no Paper plugin");
        return plugin;
    }

    private static <T> BiConsumer<T, Throwable> successOnly(Consumer<? super T> action) {
        Objects.requireNonNull(action, "action");
        return (value, error) -> {
            if (error != null) throw new CompletionException(error);
            action.accept(value);
        };
    }

    private static Throwable unwrap(Throwable error) {
        if ((error instanceof CompletionException
            || error instanceof java.util.concurrent.ExecutionException)
            && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    public static final class CallbackUnavailableException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public CallbackUnavailableException() {
            super("Paper/Folia callback target is no longer available");
        }
    }
}
