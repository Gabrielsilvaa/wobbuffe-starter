package com.wobbuffe.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

public record OperationEnvelope(
        int schemaVersion,
        String operationId,
        String operationType,
        String integrationId,
        Instant createdAt,
        String idempotencyKey,
        JsonNode payload,
        String correlationId) {
    public OperationEnvelope {
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        require(operationId, "operationId");
        require(operationType, "operationType");
        require(integrationId, "integrationId");
        require(idempotencyKey, "idempotencyKey");
        require(correlationId, "correlationId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(payload, "payload");
        if (!payload.isObject() && !payload.isArray() && !payload.isValueNode()) {
            throw new IllegalArgumentException("payload must be a JSON value");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
