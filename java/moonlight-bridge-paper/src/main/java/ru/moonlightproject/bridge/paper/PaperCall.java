package ru.moonlightproject.bridge.paper;

import java.io.Serial;
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

/**
 * A non-blocking RPC result that can only enter Bukkit state through a scheduler.
 *
 * @param <T> asynchronous result type
 */
public final class PaperCall<T> {
    private final CompletionStage<T> source;
    private final Plugin plugin;
    private final BooleanSupplier active;

    PaperCall(CompletionStage<T> source, Plugin plugin, BooleanSupplier active) {
        this.source = Objects.requireNonNull(source, "source");
        this.plugin = plugin;
        this.active = Objects.requireNonNull(active, "active");
    }

    /**
     * Runs a successful completion against server-global state.
     *
     * @param action successful result callback
     * @return future completed after callback execution
     */
    public CompletableFuture<Void> thenOnGlobal(Consumer<? super T> action) {
        return whenCompleteOnGlobal(successOnly(action));
    }

    /**
     * Runs a successful completion on the region owning {@code location}.
     *
     * @param location location whose region scheduler owns the callback
     * @param action successful result callback
     * @return future completed after callback execution
     */
    public CompletableFuture<Void> thenAt(Location location, Consumer<? super T> action) {
        return whenCompleteAt(location, successOnly(action));
    }

    /**
     * Runs a successful completion through the entity scheduler.
     *
     * @param entity entity whose scheduler owns the callback
     * @param action successful result callback
     * @return future completed after callback execution
     */
    public CompletableFuture<Void> thenFor(Entity entity, Consumer<? super T> action) {
        return whenCompleteFor(entity, successOnly(action));
    }

    /**
     * Runs success or failure completion against server-global state.
     *
     * @param action callback receiving either a value or failure
     * @return future completed after callback execution
     */
    public CompletableFuture<Void> whenCompleteOnGlobal(BiConsumer<? super T, ? super Throwable> action) {
        return whenComplete(PaperDispatchers.global(requirePlugin()), action);
    }

    /**
     * Runs success or failure completion on the region owning {@code location}.
     *
     * @param location location whose region scheduler owns the callback
     * @param action callback receiving either a value or failure
     * @return future completed after callback execution
     */
    public CompletableFuture<Void> whenCompleteAt(
        Location location,
        BiConsumer<? super T, ? super Throwable> action
    ) {
        return whenComplete(PaperDispatchers.at(requirePlugin(), location), action);
    }

    /**
     * Runs success or failure completion through the entity scheduler.
     *
     * @param entity entity whose scheduler owns the callback
     * @param action callback receiving either a value or failure
     * @return future completed after callback execution
     */
    public CompletableFuture<Void> whenCompleteFor(
        Entity entity,
        BiConsumer<? super T, ? super Throwable> action
    ) {
        return whenComplete(PaperDispatchers.forEntity(requirePlugin(), entity), action);
    }

    /**
     * Dispatches according to whether the sender is an entity, block, or global sender.
     *
     * @param sender command sender determining the safe scheduler
     * @param action callback receiving either a value or failure
     * @return future completed after callback execution
     */
    public CompletableFuture<Void> whenCompleteFor(
        CommandSender sender,
        BiConsumer<? super T, ? super Throwable> action
    ) {
        return whenComplete(PaperDispatchers.forSender(requirePlugin(), sender), action);
    }

    /**
     * Runs success or failure completion through a custom dispatcher.
     *
     * @param dispatcher scheduler adapter responsible for safe execution
     * @param action callback receiving either a value or failure
     * @return future completed after callback execution
     */
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
            try {
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
            } catch (RuntimeException dispatchError) {
                scheduled.completeExceptionally(new CallbackDispatchException(dispatchError));
            }
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

    /** Indicates that a plugin, entity, or region target retired before callback execution. */
    public static final class CallbackUnavailableException extends IllegalStateException {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates an unavailable-target exception. */
        public CallbackUnavailableException() {
            super("Paper/Folia callback target is no longer available");
        }
    }

    /** A scheduler/programming failure, distinct from a retired or disabled callback target. */
    public static final class CallbackDispatchException extends IllegalStateException {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Wraps the scheduler or programming failure that prevented dispatch.
         *
         * @param cause scheduler or callback-dispatch failure
         */
        public CallbackDispatchException(Throwable cause) {
            super("Paper/Folia scheduler failed to dispatch the callback", cause);
        }
    }
}
