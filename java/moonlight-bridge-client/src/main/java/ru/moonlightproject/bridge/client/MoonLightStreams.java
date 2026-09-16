package ru.moonlightproject.bridge.client;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.Function;

/** Dependency-free operators for generated typed streaming clients. */
public final class MoonLightStreams {
    private MoonLightStreams() { }

    /**
     * Lazily maps every item while preserving downstream demand and cancellation.
     *
     * @param source publisher to transform
     * @param mapper synchronous item mapper
     * @param <T> source item type
     * @param <R> mapped item type
     * @return publisher forwarding demand, cancellation, completion, and errors
     */
    public static <T, R> Flow.Publisher<R> map(
        Flow.Publisher<T> source, Function<? super T, ? extends R> mapper
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mapper, "mapper");
        return downstream -> source.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription upstream;

            @Override public void onSubscribe(Flow.Subscription subscription) {
                upstream = subscription;
                downstream.onSubscribe(subscription);
            }

            @Override public void onNext(T item) {
                try { downstream.onNext(mapper.apply(item)); }
                catch (RuntimeException error) {
                    upstream.cancel();
                    downstream.onError(error);
                }
            }

            @Override public void onError(Throwable error) { downstream.onError(error); }
            @Override public void onComplete() { downstream.onComplete(); }
        });
    }
}
