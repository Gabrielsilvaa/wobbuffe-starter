package com.wobbuffe.consumer;

import com.wobbuffe.config.WobbuffeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class SqsQueueMetrics {
    private static final Logger log = LoggerFactory.getLogger(SqsQueueMetrics.class);
    private final SqsClient sqs;
    private final String queueUrl;
    private final String dlqUrl;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong dlqBacklog = new AtomicLong();
    private final MeterRegistry metrics;

    public SqsQueueMetrics(SqsClient sqs, WobbuffeProperties properties, MeterRegistry metrics) {
        this.sqs = sqs;
        this.queueUrl = properties.getConsumer().effectiveQueueUrl(properties.getSqs());
        this.dlqUrl = properties.getConsumer().getDlqUrl();
        this.metrics = metrics;
        metrics.gauge("wobbuffe.consumer.queue.backlog", backlog);
        metrics.gauge("wobbuffe.consumer.dlq.backlog", dlqBacklog);
    }

    @Scheduled(fixedDelayString = "${wobbuffe.consumer.queue-metrics-delay-millis:30000}", initialDelay = 5000)
    public void refresh() {
        try {
            Map<QueueAttributeName, String> main = attributes(queueUrl);
            backlog.set(parse(main.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                    + parse(main.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                    + parse(main.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED)));
            if (dlqUrl != null && !dlqUrl.isBlank()) {
                dlqBacklog.set(parse(attributes(dlqUrl).get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)));
            }
        } catch (Exception e) {
            metrics.counter("wobbuffe.consumer.queue.metrics.errors").increment();
            log.debug("Could not refresh SQS backlog metrics: {}", e.getClass().getSimpleName());
        }
    }

    private Map<QueueAttributeName, String> attributes(String url) {
        return sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED).build()).attributes();
    }

    private long parse(String value) {
        try { return Long.parseLong(value == null ? "0" : value); }
        catch (NumberFormatException e) { return 0; }
    }
}
