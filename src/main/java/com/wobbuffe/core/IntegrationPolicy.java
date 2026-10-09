package com.wobbuffe.core;

import java.time.Duration;
import java.util.Objects;

public record IntegrationPolicy(
        String integrationId,
        boolean enabled,
        Duration timeout,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        double jitterRatio,
        int failureRateThreshold,
        int slowCallRateThreshold,
        Duration slowCallDurationThreshold,
        int minimumCalls,
        Duration breakerWindow,
        Duration breakerOpenWait,
        int halfOpenPermittedCalls,
        Duration latencyWindow,
        int latencyMinimumSamples,
        Duration degradedLatencyThreshold,
        int degradedErrorRateThreshold,
        boolean asyncEligible) {

    public IntegrationPolicy {
        if (integrationId == null || integrationId.isBlank()) throw new IllegalArgumentException("integrationId is required");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        Objects.requireNonNull(slowCallDurationThreshold, "slowCallDurationThreshold");
        Objects.requireNonNull(breakerWindow, "breakerWindow");
        Objects.requireNonNull(breakerOpenWait, "breakerOpenWait");
        Objects.requireNonNull(latencyWindow, "latencyWindow");
        Objects.requireNonNull(degradedLatencyThreshold, "degradedLatencyThreshold");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        if (initialBackoff.isNegative() || maxBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("backoff durations are invalid");
        }
        if (jitterRatio < 0 || jitterRatio > 1) throw new IllegalArgumentException("jitterRatio must be between 0 and 1");
        if (failureRateThreshold < 1 || failureRateThreshold > 100 || slowCallRateThreshold < 1 || slowCallRateThreshold > 100) {
            throw new IllegalArgumentException("breaker thresholds must be between 1 and 100");
        }
        if (minimumCalls < 1 || halfOpenPermittedCalls < 1 || latencyMinimumSamples < 1) {
            throw new IllegalArgumentException("sample counts must be at least 1");
        }
        if (degradedErrorRateThreshold < 1 || degradedErrorRateThreshold > 100) {
            throw new IllegalArgumentException("degradedErrorRateThreshold must be between 1 and 100");
        }
    }

    public static IntegrationPolicy defaults(String integrationId) {
        return new IntegrationPolicy(integrationId, true, Duration.ofSeconds(2), 3,
                Duration.ofMillis(200), Duration.ofSeconds(2), 0.2,
                50, 50, Duration.ofSeconds(2), 10, Duration.ofSeconds(30),
                Duration.ofSeconds(30), 3, Duration.ofSeconds(30), 10,
                Duration.ofSeconds(2), 50, false);
    }
}
