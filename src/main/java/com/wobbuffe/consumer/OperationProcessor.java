package com.wobbuffe.consumer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wobbuffe.config.WobbuffeProperties;
import com.wobbuffe.core.CircuitOpenException;
import com.wobbuffe.core.OperationEnvelope;
import com.wobbuffe.core.ResilienceExecutor;
import com.wobbuffe.core.ResilienceResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public final class OperationProcessor {
    private static final Logger log = LoggerFactory.getLogger(OperationProcessor.class);
    private final SqsClient sqs;
    private final String queueUrl;
    private final WobbuffeProperties rootProperties;
    private final WobbuffeProperties.Consumer properties;
    private final ObjectMapper mapper;
    private final HandlerRegistry handlers;
    private final OperationLifecycle lifecycle;
    private final ResilienceExecutor resilience;
    private final TokenBucket rateLimiter;
    private final MeterRegistry metrics;
    private final AtomicLong oldestReceivedAgeSeconds = new AtomicLong();

    public OperationProcessor(SqsClient sqs, String queueUrl, WobbuffeProperties rootProperties,
                              ObjectMapper mapper, HandlerRegistry handlers, OperationLifecycle lifecycle,
                              ResilienceExecutor resilience, TokenBucket rateLimiter, MeterRegistry metrics) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.rootProperties = rootProperties;
        this.properties = rootProperties.getConsumer();
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.handlers = handlers;
        this.lifecycle = lifecycle;
        this.resilience = resilience;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        metrics.gauge("wobbuffe.consumer.rate.tokens", rateLimiter, TokenBucket::availableTokens);
        metrics.gauge("wobbuffe.consumer.message.received.age.seconds", oldestReceivedAgeSeconds);
    }

    public void process(Message message) {
        long started = System.nanoTime();
        OperationEnvelope operation;
        try {
            String body = message.body();
            int bytes = body.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > properties.getMaxMessageBytes()) throw new IllegalArgumentException("MESSAGE_TOO_LARGE");
            operation = mapper.readValue(body, OperationEnvelope.class);
            validate(operation);
            handlers.require(operation.operationType(), operation.integrationId());
        } catch (Exception e) {
            metrics.counter("wobbuffe.consumer.messages", "result", "invalid").increment();
            log.warn("Rejected malformed or unsupported SQS operation code={}", safeCode(e));
            return; // Leave poison messages for the SQS redrive policy and DLQ.
        }

        recordReceivedAge(message);
        if (receiveCount(message) > 1) metrics.counter("wobbuffe.consumer.messages", "result", "redelivery").increment();
        if (!rateLimiter.tryAcquire()) {
            defer(message, 2, "rate_limited");
            return;
        }

        try {
            lifecycle.processing(operation);
            var handler = handlers.require(operation.operationType(), operation.integrationId());
            var execution = resilience.execute(rootProperties.policyFor(operation.integrationId()),
                    () -> handler.handle(operation));
            if (!(execution instanceof ResilienceResult.Completed<?> completed)) {
                throw new IllegalStateException("Consumer handler unexpectedly routed another operation");
            }
            JsonNode result = (JsonNode) completed.value();
            lifecycle.succeeded(operation, result);
            acknowledge(message);
            metrics.counter("wobbuffe.consumer.messages", "result", "succeeded").increment();
            Timer.builder("wobbuffe.consumer.operation.duration").tag("operation", operation.operationType())
                    .register(metrics).record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            log.info("Processed asynchronous operation operationId={} integration={} correlationId={}",
                    operation.operationId(), operation.integrationId(), operation.correlationId());
        } catch (PermanentOperationException e) {
            markFailed(operation, e.code());
            acknowledge(message);
            metrics.counter("wobbuffe.consumer.messages", "result", "permanent_failure").increment();
            log.warn("Permanent operation failure operationId={} correlationId={} code={}",
                    operation.operationId(), operation.correlationId(), e.code());
        } catch (CircuitOpenException e) {
            defer(message, Math.min(properties.getVisibilityTimeoutSeconds(), 30), "circuit_open");
        } catch (Exception e) {
            PermanentOperationException permanent = findCause(e, PermanentOperationException.class);
            if (permanent != null) {
                markFailed(operation, permanent.code());
                acknowledge(message);
                metrics.counter("wobbuffe.consumer.messages", "result", "permanent_failure").increment();
                log.warn("Permanent operation failure operationId={} correlationId={} code={}",
                        operation.operationId(), operation.correlationId(), permanent.code());
                return;
            }
            int count = receiveCount(message);
            metrics.counter("wobbuffe.consumer.messages", "result", "retryable_failure").increment();
            if (count >= properties.getMaxReceiveCount()) markFailed(operation, "RETRIES_EXHAUSTED");
            int delay = Math.min(30, Math.max(2, 1 << Math.min(count, 4)));
            defer(message, delay, "retryable_failure");
            log.warn("Retryable operation failure operationId={} correlationId={} receiveCount={} type={}",
                    operation.operationId(), operation.correlationId(), count, e.getClass().getSimpleName());
        }
    }

    private void validate(OperationEnvelope operation) {
        if (operation.schemaVersion() != 1) throw new IllegalArgumentException("UNSUPPORTED_SCHEMA_VERSION");
        if (operation.operationId().length() > 80 || operation.idempotencyKey().length() > 160
                || operation.correlationId().length() > 160 || operation.operationType().length() > 100
                || operation.integrationId().length() > 100) {
            throw new IllegalArgumentException("INVALID_OPERATION_METADATA");
        }
    }

    private int receiveCount(Message message) {
        try { return Integer.parseInt(message.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1")); }
        catch (NumberFormatException e) { return 1; }
    }

    private void recordReceivedAge(Message message) {
        String timestamp = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_FIRST_RECEIVE_TIMESTAMP);
        try {
            long age = Math.max(0, (System.currentTimeMillis() - Long.parseLong(timestamp)) / 1000);
            oldestReceivedAgeSeconds.accumulateAndGet(age, Math::max);
        } catch (RuntimeException ignored) { /* Attribute is optional in LocalStack. */ }
    }

    private void markFailed(OperationEnvelope operation, String code) {
        try { lifecycle.failed(operation, code); }
        catch (Exception failure) { log.warn("Could not persist failed operation operationId={}", operation.operationId()); }
    }

    private void defer(Message message, int seconds, String reason) {
        try {
            sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder().queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle()).visibilityTimeout(seconds).build());
            metrics.counter("wobbuffe.consumer.messages", "result", reason).increment();
        } catch (Exception e) { log.warn("Could not defer SQS message reason={}", reason); }
    }

    private void acknowledge(Message message) {
        try {
            sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle()).build());
        } catch (Exception e) {
            metrics.counter("wobbuffe.consumer.acknowledgements", "result", "delete_failed").increment();
            log.warn("Could not delete completed SQS message; it will be delivered again");
        }
    }

    private String safeCode(Exception e) {
        if (e instanceof IllegalArgumentException && e.getMessage() != null) return e.getMessage();
        return "MALFORMED_MESSAGE";
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getCause();
        }
        return null;
    }
}
