package com.wobbuffe.core;

import com.wobbuffe.config.WobbuffeProperties;
import com.wobbuffe.sqs.OperationPublisher;
import com.wobbuffe.store.OperationStore;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

public final class ResilienceExecutor {
    private static final Logger log = LoggerFactory.getLogger(ResilienceExecutor.class);
    private final WobbuffeProperties properties;
    private final ExecutorService ioExecutor;
    private final MeterRegistry meters;
    private final FailureClassifier classifier;
    private final DegradationMonitor degradationMonitor;
    private final AdaptiveRouter router;
    private final OperationPublisher publisher;
    private final OperationStore store;
    private final RandomGenerator random;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public ResilienceExecutor(WobbuffeProperties properties, ExecutorService ioExecutor, MeterRegistry meters,
                              FailureClassifier classifier, DegradationMonitor degradationMonitor,
                              AdaptiveRouter router, OperationPublisher publisher, OperationStore store,
                              RandomGenerator random) {
        this.properties = properties; this.ioExecutor = ioExecutor; this.meters = meters; this.classifier = classifier;
        this.degradationMonitor = degradationMonitor; this.router = router; this.publisher = publisher;
        this.store = store; this.random = random;
    }

    public <T> ResilienceResult<T> execute(IntegrationPolicy policy, Supplier<T> call) {
        return executeInternal(policy, null, call);
    }

    public <T> ResilienceResult<T> execute(IntegrationPolicy policy, OperationEnvelope envelope, Supplier<T> call) {
        if (envelope == null) throw new IllegalArgumentException("envelope is required for asynchronous routing");
        if (!envelope.integrationId().equals(policy.integrationId())) throw new IllegalArgumentException("envelope integrationId does not match policy");
        return executeInternal(policy, envelope, call);
    }

    /** Publishes an eligible operation without attempting the upstream call or applying the adaptive sample. */
    public <T> ResilienceResult<T> enqueue(IntegrationPolicy policy, OperationEnvelope envelope) {
        if (envelope == null) throw new IllegalArgumentException("envelope is required for asynchronous routing");
        if (!envelope.integrationId().equals(policy.integrationId())) throw new IllegalArgumentException("envelope integrationId does not match policy");
        if (!policy.asyncEligible()) throw new OperationRoutingUnavailableException("This integration is not eligible for asynchronous execution");
        RoutingDecision decision = route(policy, envelope, true, true);
        if (decision == RoutingDecision.REJECTED) throw new OperationRejectedException("Idempotency key is already reserved");
        return new ResilienceResult.Queued<>(envelope.operationId());
    }

    public <T> ResilienceResult<T> execute(String integrationId, Supplier<T> call) {
        return execute(properties.policyFor(integrationId), call);
    }

    private <T> ResilienceResult<T> executeInternal(IntegrationPolicy policy, OperationEnvelope envelope, Supplier<T> call) {
        if (!policy.enabled()) return new ResilienceResult.Completed<>(call.get());
        CircuitBreaker breaker = breakers.computeIfAbsent(policy.integrationId(), ignored -> createBreaker(policy));
        boolean degraded = breaker.getState() == CircuitBreaker.State.OPEN || degradationMonitor.isDegraded(policy);
        RoutingDecision routingDecision = envelope != null && policy.asyncEligible()
                ? route(policy, envelope, degraded, false) : RoutingDecision.EXECUTE_INLINE;
        if (routingDecision == RoutingDecision.QUEUED) {
            return new ResilienceResult.Queued<>(envelope.operationId());
        }
        if (routingDecision == RoutingDecision.REJECTED) throw new OperationRejectedException("Idempotency key is already reserved");

        long started = System.nanoTime();
        Throwable lastFailure = null;
        try {
            for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
                try {
                    T value = breaker.executeSupplier(() -> callWithTimeout(policy, call));
                    record(policy, started, false);
                    meters.counter("wobbuffe.integration.calls", "integration", policy.integrationId(), "outcome", "success").increment();
                    return new ResilienceResult.Completed<>(value);
                } catch (CallNotPermittedException e) {
                    throw new CircuitOpenException(policy.integrationId(), e);
                } catch (RuntimeException failure) {
                    lastFailure = classifier.unwrap(failure);
                    boolean retry = classifier.isRetryable(lastFailure) && attempt < policy.maxAttempts();
                    meters.counter("wobbuffe.integration.attempts", "integration", policy.integrationId(), "outcome", "failure").increment();
                    if (!retry) break;
                    sleep(backoff(policy, attempt));
                }
            }
        } catch (CircuitOpenException e) {
            record(policy, started, true);
            meters.counter("wobbuffe.integration.calls", "integration", policy.integrationId(), "outcome", "circuit_open").increment();
            throw e;
        } catch (RuntimeException e) {
            lastFailure = e;
        }
        record(policy, started, true);
        meters.counter("wobbuffe.integration.calls", "integration", policy.integrationId(), "outcome", "failure").increment();
        if (lastFailure instanceof HttpStatusException http) {
            meters.counter("wobbuffe.integration.http.responses", "integration", policy.integrationId(),
                    "status", Integer.toString(http.statusCode())).increment();
        }
        if (lastFailure instanceof IntegrationTimeoutException timeout) throw timeout;
        throw new ResilienceExecutionException("Integration '" + policy.integrationId() + "' failed after configured attempts", lastFailure);
    }

    private RoutingDecision route(IntegrationPolicy policy, OperationEnvelope envelope, boolean degraded, boolean force) {
        if (!properties.getRouting().isEnabled() || publisher == null || store == null) {
            if (force) throw new OperationRoutingUnavailableException("Asynchronous routing is not configured");
            return RoutingDecision.EXECUTE_INLINE;
        }
        meters.gauge("wobbuffe.integration.pending", List.of(io.micrometer.core.instrument.Tag.of("integration", policy.integrationId())), store, OperationStore::pendingCount);
        if (store.pendingCount() >= properties.getRouting().getMaxPending()) {
            if (force) throw new OperationRoutingUnavailableException("The asynchronous operation queue is at capacity");
            return RoutingDecision.EXECUTE_INLINE;
        }
        meters.gauge("wobbuffe.integration.routing.percent", Tags.of("integration", policy.integrationId()),
                router, current -> current.currentPercent(policy.integrationId()));
        if (!force && !router.shouldRoute(policy.integrationId(), degraded)) {
            meters.counter("wobbuffe.integration.routing.decisions", "integration", policy.integrationId(),
                    "decision", "inline").increment();
            return RoutingDecision.EXECUTE_INLINE;
        }
        if (!force && !degraded) return RoutingDecision.EXECUTE_INLINE;
        if (!store.reserve(envelope)) {
            return RoutingDecision.REJECTED;
        }
        try {
            publisher.publish(envelope);
            store.markPublished(envelope.operationId());
            meters.counter("wobbuffe.integration.routed", "integration", policy.integrationId()).increment();
            meters.counter("wobbuffe.integration.publication", "integration", policy.integrationId(), "outcome", "success").increment();
            meters.counter("wobbuffe.integration.routing.decisions", "integration", policy.integrationId(),
                    "decision", force ? "forced_queue" : "queued").increment();
            log.info("Accepted operation for asynchronous execution integration={} operationId={} correlationId={}",
                    policy.integrationId(), envelope.operationId(), envelope.correlationId());
            return RoutingDecision.QUEUED;
        } catch (RuntimeException failure) {
            store.markPublishFailed(envelope.operationId(), failure.getClass().getSimpleName());
            meters.counter("wobbuffe.integration.publication", "integration", policy.integrationId(), "outcome", "failure").increment();
            log.warn("Operation publication failed integration={} operationId={} correlationId={}",
                    policy.integrationId(), envelope.operationId(), envelope.correlationId());
            throw new OperationPublicationException(envelope.operationId(), failure);
        }
    }

    private <T> T callWithTimeout(IntegrationPolicy policy, Supplier<T> call) {
        Future<T> future = ioExecutor.submit(call::get);
        try {
            return future.get(policy.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IntegrationTimeoutException(policy.integrationId(), e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResilienceExecutionException("Interrupted while waiting for integration", e);
        } catch (ExecutionException e) {
            Throwable cause = classifier.unwrap(e);
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new ResilienceExecutionException("Integration call failed", cause);
        }
    }

    private CircuitBreaker createBreaker(IntegrationPolicy policy) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(policy.failureRateThreshold())
                .slowCallRateThreshold(policy.slowCallRateThreshold())
                .slowCallDurationThreshold(policy.slowCallDurationThreshold())
                .minimumNumberOfCalls(policy.minimumCalls())
                .slidingWindowSize(Math.max(1, Math.toIntExact(policy.breakerWindow().toSeconds())))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .waitDurationInOpenState(policy.breakerOpenWait())
                .permittedNumberOfCallsInHalfOpenState(policy.halfOpenPermittedCalls())
                .recordException(classifier::isRetryable)
                .ignoreException(classifier::isPermanentHttpFailure)
                .build();
        CircuitBreaker breaker = CircuitBreaker.of(policy.integrationId(), config);
        meters.gauge("wobbuffe.integration.circuit.state", Tags.of("integration", policy.integrationId()), breaker,
                current -> switch (current.getState()) {
                    case CLOSED -> 0;
                    case OPEN -> 1;
                    case HALF_OPEN -> 2;
                    default -> 3;
                });
        return breaker;
    }

    private Duration backoff(IntegrationPolicy policy, int failedAttempt) {
        long base = policy.initialBackoff().toMillis();
        long cap = policy.maxBackoff().toMillis();
        int shift = Math.min(failedAttempt - 1, 30);
        long exponential = Math.min(cap, base > (Long.MAX_VALUE >> shift) ? cap : base << shift);
        long spread = (long) (exponential * policy.jitterRatio());
        long jitter = spread == 0 ? 0 : random.nextLong(-spread, spread + 1);
        return Duration.ofMillis(Math.max(0, exponential + jitter));
    }

    private void record(IntegrationPolicy policy, long startedNanos, boolean failed) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
        degradationMonitor.record(policy, elapsed, failed);
        if (degradationMonitor.isDegraded(policy)) {
            meters.counter("wobbuffe.integration.degraded", "integration", policy.integrationId()).increment();
        }
        Timer.builder("wobbuffe.integration.latency").tag("integration", policy.integrationId()).register(meters).record(elapsed);
    }

    private void sleep(Duration duration) {
        try { TimeUnit.MILLISECONDS.sleep(duration.toMillis()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ResilienceExecutionException("Interrupted during retry backoff", e); }
    }
}
