package com.wobbuffe.autoconfigure;

import com.wobbuffe.config.WobbuffeProperties;
import com.wobbuffe.core.ResilienceExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
public class WobbuffeAopAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ResilientIntegrationAspect resilientIntegrationAspect(ResilienceExecutor executor, WobbuffeProperties properties) {
        return new ResilientIntegrationAspect(executor, properties);
    }
}
