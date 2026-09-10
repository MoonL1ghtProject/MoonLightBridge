package ru.moonlightproject.bridge.paper;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PaperCallMain {
    public static void main(String[] args) {
        successfulCompletionIsDispatched();
        wrappedFailureIsUnwrapped();
        closedBridgeRejectsCallback();
        retiredTargetRejectsCallback();
        schedulerFailureIsNotReportedAsRetiredTarget();
        System.out.println("MoonLightBridge Paper/Folia completion dispatch tests passed");
    }

    private static void successfulCompletionIsDispatched() {
        AtomicReference<String> value = new AtomicReference<>();
        var call = new PaperCall<>(CompletableFuture.completedFuture("ok"), null, () -> true);
        call.whenComplete(direct(), (result, error) -> value.set(result)).join();
        if (!"ok".equals(value.get())) throw new AssertionError("callback was not dispatched");
    }

    private static void wrappedFailureIsUnwrapped() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var source = CompletableFuture.<String>failedFuture(new CompletionException(new IOException("down")));
        var call = new PaperCall<>(source, null, () -> true);
        call.whenComplete(direct(), (result, error) -> failure.set(error)).join();
        if (!(failure.get() instanceof IOException)) throw new AssertionError("failure was not unwrapped");
    }

    private static void closedBridgeRejectsCallback() {
        AtomicBoolean active = new AtomicBoolean(false);
        var call = new PaperCall<>(CompletableFuture.completedFuture("late"), null, active::get);
        assertUnavailable(call.whenComplete(direct(), (result, error) -> { }));
    }

    private static void retiredTargetRejectsCallback() {
        var call = new PaperCall<>(CompletableFuture.completedFuture("late"), null, () -> true);
        assertUnavailable(call.whenComplete((action, unavailable) -> unavailable.run(), (result, error) -> { }));
    }

    private static void schedulerFailureIsNotReportedAsRetiredTarget() {
        var call = new PaperCall<>(CompletableFuture.completedFuture("late"), null, () -> true);
        try {
            call.whenComplete((action, unavailable) -> {
                throw new IllegalArgumentException("scheduler bug");
            }, (result, error) -> { }).join();
            throw new AssertionError("scheduler failure should have failed the callback");
        } catch (CompletionException error) {
            if (!(error.getCause() instanceof PaperCall.CallbackDispatchException)
                || !(error.getCause().getCause() instanceof IllegalArgumentException)) throw error;
        }
    }

    private static PaperDispatcher direct() {
        return (action, unavailable) -> action.run();
    }

    private static void assertUnavailable(CompletableFuture<Void> future) {
        try {
            future.join();
            throw new AssertionError("callback should have been rejected");
        } catch (CompletionException error) {
            if (!(error.getCause() instanceof PaperCall.CallbackUnavailableException)) throw error;
        }
    }
}
