package ru.moonlightproject.bridge.paper;

/** Schedules one completion callback and reports when its target is no longer available. */
@FunctionalInterface
public interface PaperDispatcher {
    void dispatch(Runnable action, Runnable unavailable);
}
