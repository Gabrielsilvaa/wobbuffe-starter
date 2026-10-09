package com.wobbuffe.core;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class DegradationMonitorTest {
    @Test
    void needsMinimumSamplesAndDetectsErrorRate() {
        MutableClock clock = new MutableClock();
        DegradationMonitor monitor = new DegradationMonitor(clock);
        IntegrationPolicy policy = IntegrationPolicy.defaults("catalog");
        for (int i = 0; i < 9; i++) monitor.record(policy, Duration.ofMillis(10), true);
        assertFalse(monitor.isDegraded(policy));
        monitor.record(policy, Duration.ofMillis(10), true);
        assertTrue(monitor.isDegraded(policy));
        clock.advance(Duration.ofSeconds(31));
        assertFalse(monitor.isDegraded(policy));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        @Override public long millis() { return now.toEpochMilli(); }
    }
}
