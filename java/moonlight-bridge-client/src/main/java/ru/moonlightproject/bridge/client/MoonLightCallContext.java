package ru.moonlightproject.bridge.client;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Immutable identity, deadline, metadata, and generated-policy context for one client call. */
public final class MoonLightCallContext {
    private final int methodId;
    private final long requestId;
    private final Duration deadline;
    private final RpcPolicy policy;
    private final MoonLightMetadata metadata;

    MoonLightCallContext(
        int methodId,
        long requestId,
        Duration deadline,
        RpcPolicy policy,
        MoonLightMetadata metadata
    ) {
        if (methodId == 0) throw new IllegalArgumentException("methodId must not be zero");
        if (requestId == 0) throw new IllegalArgumentException("requestId must not be zero");
        this.methodId = methodId;
        this.requestId = requestId;
        this.deadline = requirePositive(deadline);
        this.policy = policy;
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    /** Returns the generated method identifier. */
    public int methodId() { return methodId; }

    /** Returns the physical connection's request identifier. */
    public long requestId() { return requestId; }

    /** Returns the effective relative deadline. */
    public Duration deadline() { return deadline; }

    /** Returns the generated policy, or {@code null} for an untyped raw call. */
    public RpcPolicy policy() { return policy; }

    /** Returns generated authorization scopes without selecting an identity or token format. */
    public Set<String> requiredScopes() {
        return policy == null ? Set.of() : policy.requiredScopes();
    }

    /** Returns immutable user metadata to attach to the request. */
    public MoonLightMetadata metadata() { return metadata; }

    /**
     * Returns a copy carrying a tighter deadline.
     *
     * @param value positive deadline no later than the current deadline
     * @return updated immutable context
     */
    public MoonLightCallContext withDeadline(Duration value) {
        value = requirePositive(value);
        if (value.compareTo(deadline) > 0) {
            throw new IllegalArgumentException("interceptor cannot weaken the call deadline");
        }
        return new MoonLightCallContext(methodId, requestId, value, policy, metadata);
    }

    /**
     * Returns a copy carrying replacement user metadata.
     *
     * @param value immutable user metadata
     * @return updated immutable context
     */
    public MoonLightCallContext withMetadata(MoonLightMetadata value) {
        return new MoonLightCallContext(methodId, requestId, deadline, policy, value);
    }

    private static Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "deadline");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
        return value;
    }
}
