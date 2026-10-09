package com.wobbuffe.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.wobbuffe.core.OperationEnvelope;

/** Optional host-application hook for persisting operation lifecycle transitions. */
public interface OperationLifecycle {
    default void processing(OperationEnvelope operation) {}
    default void succeeded(OperationEnvelope operation, JsonNode result) {}
    default void failed(OperationEnvelope operation, String failureCode) {}
}
