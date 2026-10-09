package ru.moonlightproject.bridge.codegen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Optional protocol-v2 policy attached to an RPC method. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface MoonLightRpc {
    /** Selects server-streaming response delivery.
     * @return whether the response is a server stream */
    boolean serverStreaming() default false;
    /** Sets the overall call timeout.
     * @return overall call timeout in milliseconds */
    int timeoutMs() default 2_000;
    /** Sets the stream idle timeout.
     * @return stream idle timeout in milliseconds, or zero when disabled */
    int idleTimeoutMs() default 0;
    /** Limits retry attempts.
     * @return maximum retry attempts */
    int maxAttempts() default 1;
    /** Sets the first retry delay.
     * @return first retry backoff in milliseconds */
    int initialBackoffMs() default 25;
    /** Caps retry delay growth.
     * @return maximum retry backoff in milliseconds */
    int maxBackoffMs() default 1_000;
    /** Sets exponential retry growth.
     * @return exponential retry multiplier scaled by one thousand */
    int multiplierMilli() default 2_000;
    /** Declares the method's idempotency.
     * @return protocol idempotency mode */
    String idempotency() default "unspecified";
    /** Limits encoded request payloads.
     * @return maximum encoded request size */
    int maxRequestBytes() default 16 * 1024 * 1024;
    /** Limits encoded response payloads.
     * @return maximum encoded response size */
    int maxResponseBytes() default 16 * 1024 * 1024;
    /** Declares authorization scopes.
     * @return authorization scopes required by the service */
    String[] requiredScopes() default {};
    /** Selects the negotiated compression behavior.
     * @return negotiated compression preference */
    String compression() default "default";
    /** Sets deterministic trace sampling.
     * @return trace sampling rate per million calls */
    int traceSamplePerMillion() default 0;
}
