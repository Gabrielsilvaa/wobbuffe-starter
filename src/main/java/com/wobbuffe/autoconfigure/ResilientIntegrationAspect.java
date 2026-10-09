package com.wobbuffe.autoconfigure;

import com.wobbuffe.config.WobbuffeProperties;
import com.wobbuffe.core.ResilienceExecutor;
import com.wobbuffe.core.ResilienceResult;
import com.wobbuffe.core.ResilientIntegration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

@Aspect
public class ResilientIntegrationAspect {
    private final ResilienceExecutor executor;
    private final WobbuffeProperties properties;

    public ResilientIntegrationAspect(ResilienceExecutor executor, WobbuffeProperties properties) {
        this.executor = executor; this.properties = properties;
    }

    @Around("@annotation(annotation)")
    public Object execute(ProceedingJoinPoint joinPoint, ResilientIntegration annotation) throws Throwable {
        ResilienceResult<Object> result = executor.execute(properties.policyFor(annotation.value()), () -> {
            try { return joinPoint.proceed(); }
            catch (Throwable failure) { return sneakyThrow(failure); }
        });
        if (result instanceof ResilienceResult.Completed<Object> completed) return completed.value();
        throw new IllegalStateException("Annotated methods cannot return queued results; use ResilienceExecutor with an OperationEnvelope");
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E { throw (E) throwable; }
}
