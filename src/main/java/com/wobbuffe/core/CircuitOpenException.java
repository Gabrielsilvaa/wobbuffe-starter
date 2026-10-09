package com.wobbuffe.core;

public class CircuitOpenException extends RuntimeException {
    public CircuitOpenException(String integrationId, Throwable cause) {
        super("Circuit is open for integration '" + integrationId + "'", cause);
    }
}
