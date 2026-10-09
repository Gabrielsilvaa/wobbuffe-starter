package com.wobbuffe.autoconfigure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class WobbuffeSqsConfiguredCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        boolean producerConfigured = environment.getProperty("wobbuffe.sqs.queue-url") != null
                && !environment.getProperty("wobbuffe.sqs.queue-url").isBlank();
        boolean consumerConfigured = environment.getProperty("wobbuffe.consumer.enabled", Boolean.class, false)
                && ((environment.getProperty("wobbuffe.consumer.queue-url") != null
                && !environment.getProperty("wobbuffe.consumer.queue-url").isBlank()) || producerConfigured);
        return producerConfigured || consumerConfigured;
    }
}
