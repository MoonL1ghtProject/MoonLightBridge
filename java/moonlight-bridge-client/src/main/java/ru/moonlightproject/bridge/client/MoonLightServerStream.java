package ru.moonlightproject.bridge.client;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicReference<Flow.Subscriber<? super T>> subscriber = new AtomicReference<>();
    private final AtomicLong demand = new AtomicLong();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private volatile Throwable terminalError;

    MoonLightServerStream(LongConsumer grantCredit, Runnable cancelRemote, Consumer<Throwable> terminalCleanup) {
        this.grantCredit = grantCredit;
        this.cancelRemote = cancelRemote;
        this.terminalCleanup = terminalCleanup;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> next) {
        Objects.requireNonNull(next, "subscriber");
        if (!subscriber.compareAndSet(null, next)) {
            next.onSubscribe(new EmptySubscription());
            next.onError(new IllegalStateException("MoonLightBridge streams support one subscriber"));
            return;
        }
        next.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean cancelled = new AtomicBoolean();

            @Override
            public void request(long count) {
                if (cancelled.get() || terminated.get()) return;
                if (count <= 0) {
                    signalError(new IllegalArgumentException("Flow demand must be positive"));
                    cancelRemote.run();
                    return;
                }
                addDemand(count);
                grantCredit.accept(count);
            }

            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) cancelStream();
            }
        });
        if (terminated.get()) signalTerminal(next);
    }

    void emit(T item) {
        Flow.Subscriber<? super T> current = subscriber.get();
        if (terminated.get() || current == null || demand.get() == 0) {
            signalError(new IllegalStateException("server sent a stream item without delivery credit"));
            return;
        }
        demand.decrementAndGet();
        try {
            current.onNext(item);
        } catch (RuntimeException error) {
            signalError(error);
            cancelRemote.run();
        }
    }

    void complete() {
        if (!terminated.compareAndSet(false, true)) return;
        terminalCleanup.accept(null);
        Flow.Subscriber<? super T> current = subscriber.get();
        if (current != null) current.onComplete();
    }

    void fail(Throwable error) {
        terminalError = Objects.requireNonNull(error, "error");
        if (!terminated.compareAndSet(false, true)) return;
        terminalCleanup.accept(error);
        Flow.Subscriber<? super T> current = subscriber.get();
        if (current != null) current.onError(error);
    }

    private void signalError(Throwable error) {
        fail(error);
    }

    private void signalTerminal(Flow.Subscriber<? super T> current) {
        Throwable error = terminalError;
        if (error == null) current.onComplete(); else current.onError(error);
    }

    private void cancelStream() {
        if (!terminated.compareAndSet(false, true)) return;
        cancelRemote.run();
        terminalCleanup.accept(new java.util.concurrent.CancellationException("stream cancelled"));
    }

    private void addDemand(long count) {
        demand.getAndUpdate(current -> {
            long sum = current + count;
            return sum < 0 ? Long.MAX_VALUE : sum;
        });
    }

    @Override
    public void close() {
        cancelStream();
    }

    private static final class EmptySubscription implements Flow.Subscription {
        @Override public void request(long count) { }
        @Override public void cancel() { }
    }
}
