package ru.moonlightproject.bridge.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Shared exactly-once completion handle for one client drain transition. */
public final class MoonLightDrainHandle {
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    MoonLightDrainHandle() { }

    void complete() { completion.complete(null); }

    void fail(Throwable failure) { completion.completeExceptionally(failure); }

    /** Returns the shared terminal drain stage.
     * @return stage completed when drain finishes or fails
     */
    public CompletionStage<Void> completion() { return completion; }

    /** Returns whether drain completed successfully or exceptionally.
     * @return {@code true} after terminal completion
     */
    public boolean isDone() { return completion.isDone(); }
}
