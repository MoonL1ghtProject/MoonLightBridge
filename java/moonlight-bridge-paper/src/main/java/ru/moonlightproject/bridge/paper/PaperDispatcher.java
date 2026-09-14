package ru.moonlightproject.bridge.paper;

/** Schedules one completion callback and reports when its target is no longer available. */
@FunctionalInterface
public interface PaperDispatcher {
    /** Schedules {@code action}, invoking {@code unavailable} if the target has retired. */
    void dispatch(Runnable action, Runnable unavailable);
}
