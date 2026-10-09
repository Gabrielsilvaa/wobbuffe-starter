package com.wobbuffe.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class FailureClassifierTest {
    private final FailureClassifier classifier = new FailureClassifier();

    @Test
    void retriesOnlyConfiguredHttpStatuses() {
        for (int status : new int[]{408, 429, 500, 502, 503, 504}) {
            assertTrue(classifier.isRetryable(new HttpStatusException(status, "transient")), "status=" + status);
        }
        for (int status : new int[]{400, 401, 403, 404, 501, 505}) {
            assertFalse(classifier.isRetryable(new HttpStatusException(status, "permanent")), "status=" + status);
        }
    }

    @Test
    void retriesNetworkAndTimeoutFailures() {
        assertTrue(classifier.isRetryable(new IOException("network")));
        assertTrue(classifier.isRetryable(new IntegrationTimeoutException("catalog", null)));
        assertTrue(classifier.isPermanentHttpFailure(new HttpStatusException(400, "bad request")));
        assertFalse(classifier.isPermanentHttpFailure(new HttpStatusException(429, "rate limited")));
    }
}
