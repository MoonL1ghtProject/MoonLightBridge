package ru.moonlightproject.bridge.client;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.Consumer;

/**
 * A single-subscriber, credit-controlled server-streaming RPC.
 *
 * @param <T> decoded item type delivered to the subscriber
 */
public final class MoonLightServerStream<T> implements Flow.Publisher<T>, AutoCloseable {
    private final LongConsumer grantCredit;
    private final Runnable cancelRemote;
    private final Consumer<Throwable> terminalCleanup;
    private final Executor callbackExecutor;
    private final ConcurrentLinkedQueue<Runnable> callbackQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainingCallbacks = new AtomicBoolean();
    private final Object callbacks = new Object();
    private final AtomicLong demand = new AtomicLong();
    private Flow.Subscriber<? super T> subscriber;
    private boolean subscribing;
    private boolean terminalDelivered;
    private volatile boolean terminated;
    private boolean cancelled;
    private Throwable terminalError;

    MoonLightServerStream(LongConsumer grantCredit, Runnable cancelRemote, Consumer<Throwable> terminalCleanup) {
        this(grantCredit, cancelRemote, terminalCleanup, Runnable::run);
    }

    MoonLightServerStream(
        LongConsumer grantCredit, Runnable cancelRemote, Consumer<Throwable> terminalCleanup,
        Executor callbackExecutor
    ) {
        this.grantCredit = grantCredit;
        this.cancelRemote = cancelRemote;
        this.terminalCleanup = terminalCleanup;
        this.callbackExecutor = callbackExecutor;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> next) {
        Objects.requireNonNull(next, "subscriber");
        synchronized (callbacks) {
            if (subscriber != null) {
                try {
                    next.onSubscribe(new EmptySubscription());
                    next.onError(new IllegalStateException("MoonLightBridge streams support one subscriber"));
                } catch (Throwable ignored) { }
                return;
            }
            subscriber = next;
            subscribing = true;
            try {
                next.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long count) {
                        if (terminated) return;
                        if (count <= 0) {
                            fail(new IllegalArgumentException("Flow demand must be positive"));
                            cancelRemote.run();
                            return;
                        }
                        demand.getAndUpdate(current -> current + count < 0 ? Long.MAX_VALUE : current + count);
                        grantCredit.accept(count);
                    }
                    @Override public void cancel() { cancelStream(); }
                });
            } catch (Throwable error) {
                cancelStream();
            } finally {
                subscribing = false;
                signalTerminal();
            }
        }
    }

    void emit(T item) {
        synchronized (callbacks) {
            if (terminated) return;
            if (subscriber == null || demand.get() == 0) {
                fail(new IllegalStateException("server sent a stream item without delivery credit"));
                cancelRemote.run();
                return;
            }
            demand.getAndUpdate(current -> current == Long.MAX_VALUE ? current : current - 1);
            Flow.Subscriber<? super T> target = subscriber;
            dispatchCallback(() -> {
                try { target.onNext(item); }
                catch (Throwable error) {
                    fail(error);
                    cancelRemote.run();
                }
            });
        }
    }

    void complete() { finish(null, false); }
    void fail(Throwable error) { finish(Objects.requireNonNull(error, "error"), false); }

    private void finish(Throwable error, boolean cancel) {
        synchronized (this) {
            if (terminated) return;
            terminalError = error;
            cancelled = cancel;
            terminated = true;
        }
        try { terminalCleanup.accept(error); }
        finally {
            if (cancel) cancelRemote.run();
            else synchronized (callbacks) { signalTerminal(); }
        }
    }

    private void signalTerminal() {
        if (!terminated || subscribing || terminalDelivered || cancelled || subscriber == null) return;
        terminalDelivered = true;
        Flow.Subscriber<? super T> target = subscriber;
        Throwable error = terminalError;
        dispatchCallback(() -> {
            try {
                if (error == null) target.onComplete(); else target.onError(error);
            } catch (Throwable ignored) { /* A subscriber cannot terminate the transport. */ }
        });
    }

    private void dispatchCallback(Runnable callback) {
        callbackQueue.add(callback);
        if (!drainingCallbacks.compareAndSet(false, true)) return;
        try {
            callbackExecutor.execute(this::drainCallbacks);
        } catch (RejectedExecutionException rejected) {
            Thread.ofVirtual().name("moonlight-bridge-stream-callback").start(this::drainCallbacks);
        }
    }

    private void drainCallbacks() {
        try {
            Runnable callback;
            while ((callback = callbackQueue.poll()) != null) callback.run();
        } finally {
            drainingCallbacks.set(false);
            if (!callbackQueue.isEmpty()) dispatchCallback(() -> { });
        }
    }

    private void cancelStream() {
        finish(new java.util.concurrent.CancellationException("stream cancelled"), true);
    }

    @Override public void close() { cancelStream(); }

    private static final class EmptySubscription implements Flow.Subscription {
        @Override public void request(long count) { }
        @Override public void cancel() { }
    }
}
