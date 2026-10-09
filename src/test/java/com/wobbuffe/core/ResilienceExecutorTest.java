package com.wobbuffe.core;

import com.wobbuffe.config.WobbuffeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResilienceExecutorTest {
    private java.util.concurrent.ExecutorService io;

    @AfterEach void shutdown() { if (io != null) io.shutdownNow(); }

    @Test
    void retriesTransientFailureAndReturnsCompletedResult() {
        ResilienceExecutor executor = executor();
        AtomicInteger attempts = new AtomicInteger();
        IntegrationPolicy policy = policy("retry", 2, Duration.ofSeconds(1), 10);
        ResilienceResult<String> result = executor.execute(policy, () -> {
            if (attempts.incrementAndGet() == 1) throw new ResilienceExecutionException("wrapped I/O", new IOException("reset"));
            return "ok";
        });
        assertEquals(new ResilienceResult.Completed<>("ok"), result);
        assertEquals(2, attempts.get());
    }

    @Test
    void timeoutIsSurfacedAndOpensBreakerWhenThresholdReached() {
        ResilienceExecutor executor = executor();
        IntegrationPolicy policy = policy("timeout", 1, Duration.ofMillis(25), 1);
        assertThrows(IntegrationTimeoutException.class, () -> executor.execute(policy, () -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "late";
        }));
        assertThrows(CircuitOpenException.class, () -> executor.execute(policy, () -> "should not run"));
    }

    private ResilienceExecutor executor() {
        io = Executors.newFixedThreadPool(2);
        WobbuffeProperties properties = new WobbuffeProperties();
        return new ResilienceExecutor(properties, io, new SimpleMeterRegistry(), new FailureClassifier(),
                new DegradationMonitor(Clock.systemUTC()),
                new AdaptiveRouter(properties.getRouting(), Clock.systemUTC(), java.util.random.RandomGenerator.getDefault()),
                null, null, java.util.random.RandomGenerator.getDefault());
    }

    private IntegrationPolicy policy(String id, int attempts, Duration timeout, int minimumCalls) {
        return new IntegrationPolicy(id, true, timeout, attempts, Duration.ofMillis(1), Duration.ofMillis(1), 0,
                50, 50, Duration.ofMillis(500), minimumCalls, Duration.ofSeconds(1), Duration.ofSeconds(5), 1,
                Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 50, false);
    }

}
