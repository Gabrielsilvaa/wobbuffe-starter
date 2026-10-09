package com.wobbuffe.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.wobbuffe.core.OperationEnvelope;

/** Application-owned operation handler discovered by the starter. */
public interface OperationHandler {
    String operationType();
    String integrationId();
    JsonNode handle(OperationEnvelope operation);
}
