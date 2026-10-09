package ru.moonlightproject.bridge.client;

/** Intercepts one logical client call before enqueue and after its single terminal result. */
public interface MoonLightInterceptor {
    /**
     * Inspects or tightens an immutable call context before bytes are enqueued.
     *
     * @param context current immutable call context
     * @return context for the next interceptor
     */
    default MoonLightCallContext beforeCall(MoonLightCallContext context) {
        return context;
    }

    /**
     * Observes one terminal success, failure, timeout, or cancellation.
     * Exceptions thrown here are isolated from the call result.
     *
     * @param context final context produced by the before chain
     * @param response encoded response, or {@code null} on failure
     * @param failure terminal failure, or {@code null} on success
     */
    default void afterCall(MoonLightCallContext context, byte[] response, Throwable failure) { }
}
