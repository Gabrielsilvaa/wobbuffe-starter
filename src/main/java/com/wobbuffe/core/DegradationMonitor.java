package com.wobbuffe.core;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

public final class DegradationMonitor {
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Observation>> observations = new ConcurrentHashMap<>();

    public DegradationMonitor(Clock clock) { this.clock = clock; }

    public void record(IntegrationPolicy policy, Duration elapsed, boolean failed) {
        Deque<Observation> samples = observations.computeIfAbsent(policy.integrationId(), ignored -> new ArrayDeque<>());
        synchronized (samples) {
            samples.addLast(new Observation(clock.millis(), elapsed, failed));
            discardExpired(samples, policy.latencyWindow());
        }
    }

    public boolean isDegraded(IntegrationPolicy policy) {
        Deque<Observation> samples = observations.get(policy.integrationId());
        if (samples == null) return false;
        synchronized (samples) {
            discardExpired(samples, policy.latencyWindow());
            if (samples.size() < policy.latencyMinimumSamples()) return false;
            long failures = samples.stream().filter(Observation::failed).count();
            long errorsPercent = failures * 100 / samples.size();
            var latencies = samples.stream().map(Observation::elapsed).sorted(Comparator.naturalOrder()).toList();
            Duration p95 = latencies.get(Math.min(latencies.size() - 1, (int) Math.ceil(latencies.size() * 0.95) - 1));
            return errorsPercent >= policy.degradedErrorRateThreshold() || p95.compareTo(policy.degradedLatencyThreshold()) >= 0;
        }
    }

    private void discardExpired(Deque<Observation> samples, Duration window) {
        long cutoff = clock.millis() - window.toMillis();
        while (!samples.isEmpty() && samples.peekFirst().atMillis < cutoff) samples.removeFirst();
    }

    private record Observation(long atMillis, Duration elapsed, boolean failed) {}
}
