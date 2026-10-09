package com.wobbuffe.core;

public class OperationPublicationException extends RuntimeException {
    public OperationPublicationException(String operationId, Throwable cause) {
        super("Failed to publish operation '" + operationId + "'", cause);
    }
}
