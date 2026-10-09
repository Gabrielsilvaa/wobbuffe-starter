package com.wobbuffe.core;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

public final class FailureClassifier {
    public boolean isRetryable(Throwable throwable) {
        Throwable current = unwrap(throwable);
        if (current instanceof IntegrationTimeoutException) return true;
        if (current instanceof HttpStatusException http) {
            int status = http.statusCode();
            return status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
        }
        return current instanceof IOException || current instanceof TimeoutException || current instanceof HttpTimeoutException;
    }

    public boolean isPermanentHttpFailure(Throwable throwable) {
        Throwable current = unwrap(throwable);
        return current instanceof HttpStatusException http && http.statusCode() >= 400 && http.statusCode() < 500
                && http.statusCode() != 408 && http.statusCode() != 429;
    }

    public Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.ExecutionException || current instanceof java.util.concurrent.CompletionException
                || current instanceof ResilienceExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }
}
