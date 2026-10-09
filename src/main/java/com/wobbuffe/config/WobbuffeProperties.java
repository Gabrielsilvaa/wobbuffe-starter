package com.wobbuffe.config;

import com.wobbuffe.core.IntegrationPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Validated
@ConfigurationProperties("wobbuffe")
public class WobbuffeProperties {
    private boolean enabled = true;
    @Valid private Defaults defaults = new Defaults();
    @Valid private Routing routing = new Routing();
    @Valid private Sqs sqs = new Sqs();
    @Valid private Consumer consumer = new Consumer();
    private Map<String, @Valid Integration> integrations = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Defaults getDefaults() { return defaults; }
    public void setDefaults(Defaults defaults) { this.defaults = defaults; }
    public Routing getRouting() { return routing; }
    public void setRouting(Routing routing) { this.routing = routing; }
    public Sqs getSqs() { return sqs; }
    public void setSqs(Sqs sqs) { this.sqs = sqs; }
    public Consumer getConsumer() { return consumer; }
    public void setConsumer(Consumer consumer) { this.consumer = consumer; }
    public Map<String, Integration> getIntegrations() { return integrations; }
    public void setIntegrations(Map<String, Integration> integrations) { this.integrations = integrations; }

    public IntegrationPolicy policyFor(String integrationId) {
        Integration override = integrations.get(integrationId);
        Defaults d = defaults;
        Integration o = override == null ? new Integration() : override;
        return new IntegrationPolicy(integrationId,
                enabled && o.enabled,
                val(o.timeout, d.timeout), val(o.maxAttempts, d.maxAttempts),
                val(o.initialBackoff, d.initialBackoff), val(o.maxBackoff, d.maxBackoff),
                val(o.jitterRatio, d.jitterRatio), val(o.failureRateThreshold, d.failureRateThreshold),
                val(o.slowCallRateThreshold, d.slowCallRateThreshold), val(o.slowCallDurationThreshold, d.slowCallDurationThreshold),
                val(o.minimumCalls, d.minimumCalls), val(o.breakerWindow, d.breakerWindow), val(o.breakerOpenWait, d.breakerOpenWait),
                val(o.halfOpenPermittedCalls, d.halfOpenPermittedCalls), val(o.latencyWindow, d.latencyWindow),
                val(o.latencyMinimumSamples, d.latencyMinimumSamples), val(o.degradedLatencyThreshold, d.degradedLatencyThreshold),
                val(o.degradedErrorRateThreshold, d.degradedErrorRateThreshold), o.asyncEligible);
    }

    @AssertTrue(message = "consumer must have a queue URL when enabled")
    public boolean isConsumerConfigurationValid() {
        return !consumer.enabled || configured(consumer.queueUrl) || sqs.isConfigured();
    }

    private static <T> T val(T override, T fallback) { return override == null ? fallback : override; }

    public static class Defaults {
        @NotNull private Duration timeout = Duration.ofSeconds(2);
        @NotNull @Min(1) private Integer maxAttempts = 3;
        @NotNull private Duration initialBackoff = Duration.ofMillis(200);
        @NotNull private Duration maxBackoff = Duration.ofSeconds(2);
        @Min(0) @Max(1) private Double jitterRatio = 0.2;
        @NotNull @Min(1) @Max(100) private Integer failureRateThreshold = 50;
        @NotNull @Min(1) @Max(100) private Integer slowCallRateThreshold = 50;
        @NotNull private Duration slowCallDurationThreshold = Duration.ofSeconds(2);
        @NotNull @Min(1) private Integer minimumCalls = 10;
        @NotNull private Duration breakerWindow = Duration.ofSeconds(30);
        @NotNull private Duration breakerOpenWait = Duration.ofSeconds(30);
        @NotNull @Min(1) private Integer halfOpenPermittedCalls = 3;
        @NotNull private Duration latencyWindow = Duration.ofSeconds(30);
        @NotNull @Min(1) private Integer latencyMinimumSamples = 10;
        @NotNull private Duration degradedLatencyThreshold = Duration.ofSeconds(2);
        @NotNull @Min(1) @Max(100) private Integer degradedErrorRateThreshold = 50;

        @AssertTrue(message = "all configured durations must be positive and max-backoff must be >= initial-backoff")
        public boolean isDurationsValid() {
            return positive(timeout) && positive(initialBackoff) && positive(maxBackoff)
                    && maxBackoff.compareTo(initialBackoff) >= 0 && positive(slowCallDurationThreshold)
                    && positive(breakerWindow) && positive(breakerOpenWait) && positive(latencyWindow)
                    && positive(degradedLatencyThreshold);
        }

        public Duration getTimeout() { return timeout; } public void setTimeout(Duration v) { timeout = v; }
        public Integer getMaxAttempts() { return maxAttempts; } public void setMaxAttempts(Integer v) { maxAttempts = v; }
        public Duration getInitialBackoff() { return initialBackoff; } public void setInitialBackoff(Duration v) { initialBackoff = v; }
        public Duration getMaxBackoff() { return maxBackoff; } public void setMaxBackoff(Duration v) { maxBackoff = v; }
        public Double getJitterRatio() { return jitterRatio; } public void setJitterRatio(Double v) { jitterRatio = v; }
        public Integer getFailureRateThreshold() { return failureRateThreshold; } public void setFailureRateThreshold(Integer v) { failureRateThreshold = v; }
        public Integer getSlowCallRateThreshold() { return slowCallRateThreshold; } public void setSlowCallRateThreshold(Integer v) { slowCallRateThreshold = v; }
        public Duration getSlowCallDurationThreshold() { return slowCallDurationThreshold; } public void setSlowCallDurationThreshold(Duration v) { slowCallDurationThreshold = v; }
        public Integer getMinimumCalls() { return minimumCalls; } public void setMinimumCalls(Integer v) { minimumCalls = v; }
        public Duration getBreakerWindow() { return breakerWindow; } public void setBreakerWindow(Duration v) { breakerWindow = v; }
        public Duration getBreakerOpenWait() { return breakerOpenWait; } public void setBreakerOpenWait(Duration v) { breakerOpenWait = v; }
        public Integer getHalfOpenPermittedCalls() { return halfOpenPermittedCalls; } public void setHalfOpenPermittedCalls(Integer v) { halfOpenPermittedCalls = v; }
        public Duration getLatencyWindow() { return latencyWindow; } public void setLatencyWindow(Duration v) { latencyWindow = v; }
        public Integer getLatencyMinimumSamples() { return latencyMinimumSamples; } public void setLatencyMinimumSamples(Integer v) { latencyMinimumSamples = v; }
        public Duration getDegradedLatencyThreshold() { return degradedLatencyThreshold; } public void setDegradedLatencyThreshold(Duration v) { degradedLatencyThreshold = v; }
        public Integer getDegradedErrorRateThreshold() { return degradedErrorRateThreshold; } public void setDegradedErrorRateThreshold(Integer v) { degradedErrorRateThreshold = v; }
    }

    public static class Integration {
        private boolean enabled = true;
        private boolean asyncEligible = false;
        private Duration timeout; private Integer maxAttempts; private Duration initialBackoff; private Duration maxBackoff;
        private Double jitterRatio; private Integer failureRateThreshold; private Integer slowCallRateThreshold;
        private Duration slowCallDurationThreshold; private Integer minimumCalls; private Duration breakerWindow;
        private Duration breakerOpenWait; private Integer halfOpenPermittedCalls; private Duration latencyWindow;
        private Integer latencyMinimumSamples; private Duration degradedLatencyThreshold; private Integer degradedErrorRateThreshold;
        @AssertTrue(message = "integration override durations must be positive and max-backoff must be >= initial-backoff")
        public boolean isOverrideValid() {
            return positiveOrNull(timeout) && positiveOrNull(initialBackoff) && positiveOrNull(maxBackoff)
                    && (maxBackoff == null || initialBackoff == null || maxBackoff.compareTo(initialBackoff) >= 0)
                    && positiveOrNull(slowCallDurationThreshold) && positiveOrNull(breakerWindow)
                    && positiveOrNull(breakerOpenWait) && positiveOrNull(latencyWindow)
                    && positiveOrNull(degradedLatencyThreshold);
        }
        public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
        public boolean isAsyncEligible() { return asyncEligible; } public void setAsyncEligible(boolean v) { asyncEligible = v; }
        public Duration getTimeout() { return timeout; } public void setTimeout(Duration v) { timeout = v; }
        public Integer getMaxAttempts() { return maxAttempts; } public void setMaxAttempts(Integer v) { maxAttempts = v; }
        public Duration getInitialBackoff() { return initialBackoff; } public void setInitialBackoff(Duration v) { initialBackoff = v; }
        public Duration getMaxBackoff() { return maxBackoff; } public void setMaxBackoff(Duration v) { maxBackoff = v; }
        public Double getJitterRatio() { return jitterRatio; } public void setJitterRatio(Double v) { jitterRatio = v; }
        public Integer getFailureRateThreshold() { return failureRateThreshold; } public void setFailureRateThreshold(Integer v) { failureRateThreshold = v; }
        public Integer getSlowCallRateThreshold() { return slowCallRateThreshold; } public void setSlowCallRateThreshold(Integer v) { slowCallRateThreshold = v; }
        public Duration getSlowCallDurationThreshold() { return slowCallDurationThreshold; } public void setSlowCallDurationThreshold(Duration v) { slowCallDurationThreshold = v; }
        public Integer getMinimumCalls() { return minimumCalls; } public void setMinimumCalls(Integer v) { minimumCalls = v; }
        public Duration getBreakerWindow() { return breakerWindow; } public void setBreakerWindow(Duration v) { breakerWindow = v; }
        public Duration getBreakerOpenWait() { return breakerOpenWait; } public void setBreakerOpenWait(Duration v) { breakerOpenWait = v; }
        public Integer getHalfOpenPermittedCalls() { return halfOpenPermittedCalls; } public void setHalfOpenPermittedCalls(Integer v) { halfOpenPermittedCalls = v; }
        public Duration getLatencyWindow() { return latencyWindow; } public void setLatencyWindow(Duration v) { latencyWindow = v; }
        public Integer getLatencyMinimumSamples() { return latencyMinimumSamples; } public void setLatencyMinimumSamples(Integer v) { latencyMinimumSamples = v; }
        public Duration getDegradedLatencyThreshold() { return degradedLatencyThreshold; } public void setDegradedLatencyThreshold(Duration v) { degradedLatencyThreshold = v; }
        public Integer getDegradedErrorRateThreshold() { return degradedErrorRateThreshold; } public void setDegradedErrorRateThreshold(Integer v) { degradedErrorRateThreshold = v; }
    }

    public static class Routing {
        private boolean enabled = false;
        @Min(1) @Max(100) private int maxPercent = 25;
        @Min(1) @Max(100) private int stepPercent = 5;
        @Min(1) private long maxPending = 1000;
        private Duration evaluationInterval = Duration.ofSeconds(10);
        @AssertTrue(message = "routing evaluation interval must be positive")
        public boolean isEvaluationIntervalValid() { return positive(evaluationInterval); }
        public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
        public int getMaxPercent() { return maxPercent; } public void setMaxPercent(int v) { maxPercent = v; }
        public int getStepPercent() { return stepPercent; } public void setStepPercent(int v) { stepPercent = v; }
        public long getMaxPending() { return maxPending; } public void setMaxPending(long v) { maxPending = v; }
        public Duration getEvaluationInterval() { return evaluationInterval; } public void setEvaluationInterval(Duration v) { evaluationInterval = v; }
    }

    public static class Sqs {
        private String endpoint; private String queueUrl; @NotBlank private String region = "us-east-1";
        public String getEndpoint() { return endpoint; } public void setEndpoint(String v) { endpoint = v; }
        public String getQueueUrl() { return queueUrl; } public void setQueueUrl(String v) { queueUrl = v; }
        public String getRegion() { return region; } public void setRegion(String v) { region = v; }
        public boolean isConfigured() { return queueUrl != null && !queueUrl.isBlank(); }
    }

    public static class Consumer {
        private boolean enabled = false;
        private String queueUrl;
        private String dlqUrl;
        @Min(1) @Max(20) private int pollWaitSeconds = 20;
        @Min(1) @Max(10) private int batchSize = 5;
        @Min(1) private int visibilityTimeoutSeconds = 90;
        @Min(1) private int maxReceiveCount = 5;
        @Min(1) @Max(16) private int maxConcurrency = 2;
        @Min(1) private int rateLimitPerSecond = 5;
        @Min(1) private int maxMessageBytes = 262144;
        @Min(1) private long pollDelayMillis = 1000;
        @Min(1) private long queueMetricsDelayMillis = 30000;

        public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
        public String getQueueUrl() { return queueUrl; } public void setQueueUrl(String v) { queueUrl = v; }
        public String getDlqUrl() { return dlqUrl; } public void setDlqUrl(String v) { dlqUrl = v; }
        public int getPollWaitSeconds() { return pollWaitSeconds; } public void setPollWaitSeconds(int v) { pollWaitSeconds = v; }
        public int getBatchSize() { return batchSize; } public void setBatchSize(int v) { batchSize = v; }
        public int getVisibilityTimeoutSeconds() { return visibilityTimeoutSeconds; } public void setVisibilityTimeoutSeconds(int v) { visibilityTimeoutSeconds = v; }
        public int getMaxReceiveCount() { return maxReceiveCount; } public void setMaxReceiveCount(int v) { maxReceiveCount = v; }
        public int getMaxConcurrency() { return maxConcurrency; } public void setMaxConcurrency(int v) { maxConcurrency = v; }
        public int getRateLimitPerSecond() { return rateLimitPerSecond; } public void setRateLimitPerSecond(int v) { rateLimitPerSecond = v; }
        public int getMaxMessageBytes() { return maxMessageBytes; } public void setMaxMessageBytes(int v) { maxMessageBytes = v; }
        public long getPollDelayMillis() { return pollDelayMillis; } public void setPollDelayMillis(long v) { pollDelayMillis = v; }
        public long getQueueMetricsDelayMillis() { return queueMetricsDelayMillis; } public void setQueueMetricsDelayMillis(long v) { queueMetricsDelayMillis = v; }
        public String effectiveQueueUrl(Sqs sqs) { return configured(queueUrl) ? queueUrl : sqs.getQueueUrl(); }
    }

    private static boolean positive(Duration value) { return value != null && !value.isNegative() && !value.isZero(); }
    private static boolean positiveOrNull(Duration value) { return value == null || positive(value); }
    private static boolean configured(String value) { return value != null && !value.isBlank(); }
}
