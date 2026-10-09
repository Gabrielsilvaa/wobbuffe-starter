package com.wobbuffe.core;

public class IntegrationTimeoutException extends RuntimeException {
    public IntegrationTimeoutException(String integrationId, Throwable cause) {
        super("Integration '" + integrationId + "' exceeded its timeout", cause);
    }
}
