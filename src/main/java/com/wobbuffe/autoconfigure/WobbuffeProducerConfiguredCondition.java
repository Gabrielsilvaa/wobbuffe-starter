package com.wobbuffe.autoconfigure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class WobbuffeProducerConfiguredCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String queueUrl = context.getEnvironment().getProperty("wobbuffe.sqs.queue-url");
        return queueUrl != null && !queueUrl.isBlank();
    }
}
