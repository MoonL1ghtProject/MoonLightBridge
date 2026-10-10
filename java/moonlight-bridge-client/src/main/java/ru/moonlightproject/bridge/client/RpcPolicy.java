package ru.moonlightproject.bridge.client;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable resource, retry, authorization, compression, and sampling limits generated for one RPC.
 *
 * @param timeout overall logical-call timeout
 * @param idleTimeout maximum silence between stream items, or zero for unary methods
 * @param retry bounded retry settings
 * @param idempotency retry-safety classification
 * @param maxRequestBytes maximum decoded request bytes
 * @param maxResponseBytes maximum decoded response or stream-item bytes
 * @param requiredScopes authorization scopes required by middleware
 * @param compression per-method negotiated compression behavior
 * @param traceSamplePerMillion trace sampling probability in millionths
 * @param methodName generated canonical {@code Service/Method} name used by telemetry, or
 *                   {@code null} for a raw call
 */
public record RpcPolicy(
    Duration timeout,
    Duration idleTimeout,
    Retry retry,
    Idempotency idempotency,
    int maxRequestBytes,
    int maxResponseBytes,
    Set<String> requiredScopes,
    Compression compression,
    int traceSamplePerMillion,
    String methodName
) {
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;

    /** Validates and defensively copies an RPC policy. */
    public RpcPolicy {
        timeout = requirePositiveBounded(timeout, "timeout");
        idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isNegative() || idleTimeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("idleTimeout must be zero through ten minutes");
        }
        retry = Objects.requireNonNull(retry, "retry");
        idempotency = Objects.requireNonNull(idempotency, "idempotency");
        if (retry.maxAttempts > 1 && idempotency == Idempotency.UNSPECIFIED) {
            throw new IllegalArgumentException("retries require explicit idempotency semantics");
        }
        if (maxRequestBytes < 1 || maxRequestBytes > MAX_BODY_BYTES
            || maxResponseBytes < 1 || maxResponseBytes > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("RPC body limits must be between 1 and 64 MiB");
        }
        TreeSet<String> scopes = new TreeSet<>(Objects.requireNonNull(requiredScopes, "requiredScopes"));
        if (scopes.size() > 32 || scopes.stream().anyMatch(scope -> !validScope(scope))) {
            throw new IllegalArgumentException("invalid required RPC scope");
        }
        requiredScopes = Set.copyOf(scopes);
        compression = Objects.requireNonNull(compression, "compression");
        if (traceSamplePerMillion < 0 || traceSamplePerMillion > 1_000_000) {
            throw new IllegalArgumentException("trace sample rate must be between 0 and 1000000");
        }
        if (methodName != null && (methodName.isBlank() || methodName.length() > 256)) {
            throw new IllegalArgumentException("methodName must be non-blank and at most 256 characters");
        }
    }

    /**
     * Creates a policy without generated telemetry naming metadata.
     *
     * <p>This compatibility constructor is intended for raw or manually registered calls.
     * Generated clients use the canonical constructor so trace backends can display the readable
     * {@code Service/Method} operation.</p>
     *
     * @param timeout overall logical-call timeout
     * @param idleTimeout maximum silence between stream items, or zero for unary methods
     * @param retry bounded retry settings
     * @param idempotency retry-safety classification
     * @param maxRequestBytes maximum decoded request bytes
     * @param maxResponseBytes maximum decoded response or stream-item bytes
     * @param requiredScopes authorization scopes required by middleware
     * @param compression per-method negotiated compression behavior
     * @param traceSamplePerMillion trace sampling probability in millionths
     */
    public RpcPolicy(
        Duration timeout,
        Duration idleTimeout,
        Retry retry,
        Idempotency idempotency,
        int maxRequestBytes,
        int maxResponseBytes,
        Set<String> requiredScopes,
        Compression compression,
        int traceSamplePerMillion
    ) {
        this(timeout, idleTimeout, retry, idempotency, maxRequestBytes, maxResponseBytes,
            requiredScopes, compression, traceSamplePerMillion, null);
    }

    /**
     * Applies a caller deadline without allowing it to exceed the generated schema limit.
     *
     * @param requested caller-requested deadline
     * @return the shorter of the caller and schema deadlines
     */
    public Duration effectiveDeadline(Duration requested) {
        requested = requirePositiveBounded(requested, "requested");
        return requested.compareTo(timeout) < 0 ? requested : timeout;
    }

    private static Duration requirePositiveBounded(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative() || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(name + " must be between one nanosecond and ten minutes");
        }
        return value;
    }

    private static boolean validScope(String scope) {
        if (scope == null || scope.isEmpty() || scope.length() > 128) return false;
        for (int index = 0; index < scope.length(); index++) {
            char value = scope.charAt(index);
            if (!((value >= 'a' && value <= 'z') || (value >= '0' && value <= '9')
                || value == '.' || value == '_' || value == ':' || value == '-')) return false;
        }
        return true;
    }

    /** Retry safety declared by the method schema. */
    public enum Idempotency {
        /** The method has no retry-safety declaration. */
        UNSPECIFIED,
        /** The method has no externally visible mutation. */
        READ_ONLY,
        /** Repeating the same request has the same externally visible effect. */
        IDEMPOTENT,
        /** Every attempt must carry the same stable idempotency key. */
        KEY_REQUIRED
    }

    /** Per-method compression preference. */
    public enum Compression {
        /** Use the negotiated runtime default. */
        DEFAULT,
        /** Never compress this method's payloads. */
        DISABLED,
        /** Compress when negotiation, thresholds, and savings permit it. */
        PREFER,
        /** Fail when compression cannot be negotiated. */
        REQUIRED
    }

    /**
     * Bounded retry/backoff settings.
     *
     * @param maxAttempts total attempts including the initial call
     * @param initialBackoff delay before the first retry
     * @param maxBackoff maximum delay between attempts
     * @param multiplierMilli backoff multiplier in thousandths
     */
    public record Retry(
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        int multiplierMilli
    ) {
        /** Validates bounded retry settings. */
        public Retry {
            if (maxAttempts < 1 || maxAttempts > 8) {
                throw new IllegalArgumentException("maxAttempts must be between 1 and 8");
            }
            initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
            maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff");
            if (initialBackoff.isZero() || initialBackoff.isNegative()
                || initialBackoff.compareTo(Duration.ofMinutes(1)) > 0
                || maxBackoff.compareTo(initialBackoff) < 0
                || maxBackoff.compareTo(Duration.ofMinutes(5)) > 0
                || multiplierMilli < 1_000 || multiplierMilli > 10_000) {
                throw new IllegalArgumentException("retry backoff settings are outside safe ranges");
            }
        }
    }
}
