package ru.moonlightproject.bridge.codegen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Optional protocol-v2 policy attached to an RPC method. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface MoonLightRpc {
    boolean serverStreaming() default false;
    int timeoutMs() default 2_000;
    int idleTimeoutMs() default 0;
    int maxAttempts() default 1;
    int initialBackoffMs() default 25;
    int maxBackoffMs() default 1_000;
    int multiplierMilli() default 2_000;
    String idempotency() default "unspecified";
    int maxRequestBytes() default 16 * 1024 * 1024;
    int maxResponseBytes() default 16 * 1024 * 1024;
    String[] requiredScopes() default {};
    String compression() default "default";
    int traceSamplePerMillion() default 0;
}
