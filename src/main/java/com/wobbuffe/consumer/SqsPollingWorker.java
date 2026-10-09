package com.wobbuffe.consumer;

import com.wobbuffe.config.WobbuffeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

public final class SqsPollingWorker {
    private static final Logger log = LoggerFactory.getLogger(SqsPollingWorker.class);
    private final SqsClient sqs;
    private final String queueUrl;
    private final WobbuffeProperties.Consumer properties;
    private final OperationProcessor processor;
    private final Executor executor;
    private final Semaphore permits;
    private final MeterRegistry metrics;

    public SqsPollingWorker(SqsClient sqs, String queueUrl, WobbuffeProperties.Consumer properties,
                            OperationProcessor processor, Executor executor, MeterRegistry metrics) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.properties = properties;
        this.processor = processor;
        this.executor = executor;
        this.permits = new Semaphore(properties.getMaxConcurrency());
        this.metrics = metrics;
        metrics.gauge("wobbuffe.consumer.active", permits,
                semaphore -> properties.getMaxConcurrency() - semaphore.availablePermits());
    }

    @Scheduled(fixedDelayString = "${wobbuffe.consumer.poll-delay-millis:1000}")
    public void poll() {
        int available = permits.availablePermits();
        if (available == 0) return;
        try {
            var response = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
                    .waitTimeSeconds(properties.getPollWaitSeconds())
                    .maxNumberOfMessages(Math.min(properties.getBatchSize(), available))
                    .visibilityTimeout(properties.getVisibilityTimeoutSeconds())
                    .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                            MessageSystemAttributeName.APPROXIMATE_FIRST_RECEIVE_TIMESTAMP).build());
            for (Message message : response.messages()) dispatch(message);
        } catch (Exception e) {
            metrics.counter("wobbuffe.consumer.poll.errors").increment();
            log.warn("SQS poll failed; retrying after poll delay: {}", e.getClass().getSimpleName());
        }
    }

    private void dispatch(Message message) {
        if (!permits.tryAcquire()) { releaseImmediately(message); return; }
        metrics.counter("wobbuffe.consumer.messages", "result", "received").increment();
        try {
            executor.execute(() -> {
                try { processor.process(message); }
                catch (Exception e) { log.warn("Unexpected operation consumer failure: {}", e.getClass().getSimpleName()); }
                finally { permits.release(); }
            });
        } catch (RuntimeException rejected) {
            permits.release();
            releaseImmediately(message);
        }
    }

    private void releaseImmediately(Message message) {
        try { sqs.changeMessageVisibility(request -> request.queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle()).visibilityTimeout(0)); }
        catch (Exception ignored) { log.warn("Could not release an unprocessed message back to SQS"); }
    }
}
