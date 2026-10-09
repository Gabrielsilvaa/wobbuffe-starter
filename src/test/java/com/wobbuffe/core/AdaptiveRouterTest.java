package com.wobbuffe.core;

import com.wobbuffe.config.WobbuffeProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdaptiveRouterTest {
    @Test
    void rampsUpInStepsAndStepsDownAfterTwoHealthyWindows() {
        MutableClock clock = new MutableClock();
        WobbuffeProperties.Routing config = new WobbuffeProperties.Routing();
        config.setEnabled(true);
        AdaptiveRouter router = new AdaptiveRouter(config, clock, () -> 0L);

        router.shouldRoute("catalog", true);
        assertEquals(5, router.currentPercent("catalog"));
        clock.advance(Duration.ofSeconds(10));
        router.shouldRoute("catalog", true);
        assertEquals(10, router.currentPercent("catalog"));
        clock.advance(Duration.ofSeconds(10));
        router.shouldRoute("catalog", false);
        assertEquals(10, router.currentPercent("catalog"));
        clock.advance(Duration.ofSeconds(10));
        router.shouldRoute("catalog", false);
        assertEquals(5, router.currentPercent("catalog"));
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
