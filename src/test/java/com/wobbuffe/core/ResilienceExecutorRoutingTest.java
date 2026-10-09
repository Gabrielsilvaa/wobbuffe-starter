package com.wobbuffe.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wobbuffe.config.WobbuffeProperties;
import com.wobbuffe.sqs.OperationPublisher;
import com.wobbuffe.store.OperationStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class ResilienceExecutorRoutingTest {
    private java.util.concurrent.ExecutorService io;

    @AfterEach void shutdown() { if (io != null) io.shutdownNow(); }

    @Test
    void degradedEligibleOperationPublishesAndReturnsQueuedOnlyAfterAcceptance() throws Exception {
        WobbuffeProperties properties = routingProperties();
        InMemoryStore store = new InMemoryStore();
        AtomicBoolean published = new AtomicBoolean();
        OperationPublisher publisher = envelope -> published.set(true);
        DegradationMonitor monitor = new DegradationMonitor(Clock.systemUTC());
        IntegrationPolicy policy = policy(true);
        monitor.record(policy, Duration.ofMillis(20), true);
        ResilienceExecutor executor = executor(properties, monitor, publisher, store);
        AtomicBoolean called = new AtomicBoolean();
        OperationEnvelope envelope = envelope();

        ResilienceResult<String> result = executor.execute(policy, envelope, () -> { called.set(true); return "inline"; });

        assertEquals(new ResilienceResult.Queued<String>("op-1"), result);
        assertTrue(published.get());
        assertTrue(store.published.get());
        assertFalse(called.get());
    }

    @Test
    void publicationFailureIsSurfacedAndRecordedWithoutInlineFallback() throws Exception {
        WobbuffeProperties properties = routingProperties();
        InMemoryStore store = new InMemoryStore();
        OperationPublisher publisher = envelope -> { throw new IllegalStateException("SQS unavailable"); };
        DegradationMonitor monitor = new DegradationMonitor(Clock.systemUTC());
        IntegrationPolicy policy = policy(true);
        monitor.record(policy, Duration.ofMillis(20), true);
        ResilienceExecutor executor = executor(properties, monitor, publisher, store);
        AtomicBoolean called = new AtomicBoolean();

        assertThrows(OperationPublicationException.class, () -> executor.execute(policy, envelope(), () -> {
            called.set(true);
            return "inline";
        }));
        assertTrue(store.failed.get());
        assertFalse(called.get());
    }

    private ResilienceExecutor executor(WobbuffeProperties properties, DegradationMonitor monitor,
                                        OperationPublisher publisher, OperationStore store) {
        io = Executors.newFixedThreadPool(2);
        return new ResilienceExecutor(properties, io, new SimpleMeterRegistry(), new FailureClassifier(), monitor,
                new AdaptiveRouter(properties.getRouting(), Clock.systemUTC(), zeroRandom()), publisher, store, zeroRandom());
    }

    private WobbuffeProperties routingProperties() {
        WobbuffeProperties properties = new WobbuffeProperties();
        properties.getRouting().setEnabled(true);
        properties.getRouting().setMaxPercent(100);
        properties.getRouting().setEvaluationInterval(Duration.ofSeconds(10));
        return properties;
    }

    private IntegrationPolicy policy(boolean eligible) {
        return new IntegrationPolicy("catalog", true, Duration.ofSeconds(1), 1, Duration.ofMillis(1),
                Duration.ofMillis(1), 0, 50, 50, Duration.ofSeconds(1), 10, Duration.ofSeconds(10),
                Duration.ofSeconds(10), 1, Duration.ofSeconds(30), 1, Duration.ofMillis(10), 50, eligible);
    }

    private OperationEnvelope envelope() throws Exception {
        return new OperationEnvelope(1, "op-1", "catalog.refresh", "catalog", Instant.now(), "idem-1",
                new ObjectMapper().readTree("{\"id\":\"42\"}"), "corr-1");
    }

    private RandomGenerator zeroRandom() { return () -> 0L; }

    private static final class InMemoryStore implements OperationStore {
        private final AtomicLong count = new AtomicLong();
        private final AtomicBoolean published = new AtomicBoolean();
        private final AtomicBoolean failed = new AtomicBoolean();
        @Override public boolean reserve(String key, String operationId) { return count.getAndIncrement() == 0; }
        @Override public long pendingCount() { return count.get(); }
        @Override public void markPublished(String operationId) { published.set(true); }
        @Override public void markPublishFailed(String operationId, String reason) { failed.set(true); }
    }
}
