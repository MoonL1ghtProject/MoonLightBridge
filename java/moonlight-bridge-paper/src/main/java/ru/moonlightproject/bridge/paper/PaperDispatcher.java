package ru.moonlightproject.bridge.paper;

/** Schedules one completion callback and reports when its target is no longer available. */
@FunctionalInterface
public interface PaperDispatcher {
    /**
     * Schedules {@code action}, invoking {@code unavailable} if the target has retired.
     *
     * @param action callback to execute on the correct scheduler
     * @param unavailable fallback invoked when the scheduler target has retired
     */
    void dispatch(Runnable action, Runnable unavailable);
}
