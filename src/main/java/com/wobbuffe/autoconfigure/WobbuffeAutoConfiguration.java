package com.wobbuffe.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wobbuffe.config.WobbuffeProperties;
import com.wobbuffe.core.AdaptiveRouter;
import com.wobbuffe.core.DegradationMonitor;
import com.wobbuffe.core.FailureClassifier;
import com.wobbuffe.core.ResilienceExecutor;
import com.wobbuffe.consumer.OperationHandler;
import com.wobbuffe.consumer.OperationLifecycle;
import com.wobbuffe.consumer.HandlerRegistry;
import com.wobbuffe.consumer.OperationProcessor;
import com.wobbuffe.consumer.SqsPollingWorker;
import com.wobbuffe.consumer.SqsQueueMetrics;
import com.wobbuffe.consumer.TokenBucket;
import com.wobbuffe.sqs.OperationPublisher;
import com.wobbuffe.sqs.SqsOperationPublisher;
import com.wobbuffe.store.OperationStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.random.RandomGenerator;

@AutoConfiguration
@EnableConfigurationProperties(WobbuffeProperties.class)
public class WobbuffeAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    Clock wobbuffeClock() { return Clock.systemUTC(); }

    @Bean @ConditionalOnMissingBean
    RandomGenerator wobbuffeRandomGenerator() { return RandomGenerator.getDefault(); }

    @Bean @ConditionalOnMissingBean
    MeterRegistry wobbuffeMeterRegistry() { return new SimpleMeterRegistry(); }

    @Bean @ConditionalOnMissingBean
    FailureClassifier failureClassifier() { return new FailureClassifier(); }

    @Bean(destroyMethod = "shutdownNow") @ConditionalOnMissingBean(name = "wobbuffeIoExecutor")
    ExecutorService wobbuffeIoExecutor() {
        ThreadFactory factory = runnable -> { Thread thread = new Thread(runnable, "wobbuffe-integration"); thread.setDaemon(true); return thread; };
        return new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean @ConditionalOnMissingBean
    DegradationMonitor degradationMonitor(Clock clock) { return new DegradationMonitor(clock); }

    @Bean @ConditionalOnMissingBean
    AdaptiveRouter adaptiveRouter(WobbuffeProperties properties, Clock clock, RandomGenerator random) {
        return new AdaptiveRouter(properties.getRouting(), clock, random);
    }

    @Bean @ConditionalOnMissingBean
    ResilienceExecutor resilienceExecutor(WobbuffeProperties properties, ExecutorService wobbuffeIoExecutor,
            MeterRegistry meterRegistry, FailureClassifier classifier, DegradationMonitor degradationMonitor,
            AdaptiveRouter router, ObjectProvider<OperationPublisher> publisher, ObjectProvider<OperationStore> store,
            RandomGenerator random) {
        return new ResilienceExecutor(properties, wobbuffeIoExecutor, meterRegistry, classifier, degradationMonitor,
                router, publisher.getIfAvailable(), store.getIfAvailable(), random);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnClass(name = "software.amazon.awssdk.services.sqs.SqsClient")
    @Conditional(WobbuffeSqsConfiguredCondition.class)
    @ConditionalOnMissingBean
    software.amazon.awssdk.services.sqs.SqsClient wobbuffeSqsClient(WobbuffeProperties properties) {
        var builder = software.amazon.awssdk.services.sqs.SqsClient.builder()
                .region(software.amazon.awssdk.regions.Region.of(properties.getSqs().getRegion()));
        if (properties.getSqs().getEndpoint() != null && !properties.getSqs().getEndpoint().isBlank()) {
            builder.endpointOverride(java.net.URI.create(properties.getSqs().getEndpoint()));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnClass(name = "software.amazon.awssdk.services.sqs.SqsClient")
    @Conditional(WobbuffeProducerConfiguredCondition.class)
    @ConditionalOnMissingBean(OperationPublisher.class)
    OperationPublisher sqsOperationPublisher(software.amazon.awssdk.services.sqs.SqsClient client,
                                               WobbuffeProperties properties, ObjectMapper objectMapper) {
        return new SqsOperationPublisher(client, properties.getSqs().getQueueUrl(), objectMapper);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnClass(name = "software.amazon.awssdk.services.sqs.SqsClient")
    @ConditionalOnProperty(prefix = "wobbuffe.consumer", name = "enabled", havingValue = "true")
    static class ConsumerConfiguration {
        @Bean @ConditionalOnMissingBean
        HandlerRegistry wobbuffeHandlerRegistry(List<OperationHandler> handlers) { return new HandlerRegistry(handlers); }

        @Bean @ConditionalOnMissingBean
        OperationLifecycle wobbuffeOperationLifecycle() { return new OperationLifecycle() {}; }

        @Bean @ConditionalOnMissingBean(name = "wobbuffeConsumerExecutor")
        ExecutorService wobbuffeConsumerExecutor(WobbuffeProperties properties) {
            int concurrency = properties.getConsumer().getMaxConcurrency();
            ThreadFactory factory = task -> { Thread t = new Thread(task, "wobbuffe-consumer"); t.setDaemon(true); return t; };
            return new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(concurrency), factory, new ThreadPoolExecutor.AbortPolicy());
        }

        @Bean @ConditionalOnMissingBean
        TokenBucket wobbuffeConsumerRateLimiter(WobbuffeProperties properties) {
            return new TokenBucket(properties.getConsumer().getRateLimitPerSecond(), System::nanoTime);
        }

        @Bean @ConditionalOnMissingBean
        OperationProcessor wobbuffeOperationProcessor(software.amazon.awssdk.services.sqs.SqsClient client,
                WobbuffeProperties properties, ObjectMapper mapper, HandlerRegistry handlers,
                OperationLifecycle lifecycle, ResilienceExecutor resilience, TokenBucket rateLimiter, MeterRegistry meters) {
            return new OperationProcessor(client, properties.getConsumer().effectiveQueueUrl(properties.getSqs()),
                    properties, mapper, handlers, lifecycle, resilience, rateLimiter, meters);
        }

        @Bean @ConditionalOnMissingBean
        SqsPollingWorker wobbuffeSqsPollingWorker(software.amazon.awssdk.services.sqs.SqsClient client,
                WobbuffeProperties properties, OperationProcessor processor, ExecutorService wobbuffeConsumerExecutor,
                MeterRegistry meters) {
            return new SqsPollingWorker(client, properties.getConsumer().effectiveQueueUrl(properties.getSqs()),
                    properties.getConsumer(), processor, wobbuffeConsumerExecutor, meters);
        }

        @Bean @ConditionalOnMissingBean
        SqsQueueMetrics wobbuffeSqsQueueMetrics(software.amazon.awssdk.services.sqs.SqsClient client,
                WobbuffeProperties properties, MeterRegistry meters) {
            return new SqsQueueMetrics(client, properties, meters);
        }
    }
}
