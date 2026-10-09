package com.wobbuffe.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WobbuffeAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WobbuffeAutoConfiguration.class));

    @Test
    void createsExecutorWithoutSqsConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(com.wobbuffe.core.ResilienceExecutor.class);
            assertThat(context).doesNotHaveBean(software.amazon.awssdk.services.sqs.SqsClient.class);
            assertThat(context.getBean(com.wobbuffe.config.WobbuffeProperties.class).getRouting().isEnabled()).isFalse();
        });
    }

    @Test
    void rejectsInvalidConfiguration() {
        contextRunner.withPropertyValues("wobbuffe.defaults.max-attempts=0").run(context ->
                assertThat(context).hasFailed());
    }
}
