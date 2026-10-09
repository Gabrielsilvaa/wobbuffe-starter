package com.wobbuffe.store;

import com.wobbuffe.core.OperationEnvelope;

public interface OperationStore {
    /** Atomically reserves an idempotency key. Returns false if that key already exists. */
    boolean reserve(String idempotencyKey, String operationId);
    /** Reserves an operation with its versioned metadata when the backing store needs it for status reporting. */
    default boolean reserve(OperationEnvelope envelope) {
        return reserve(envelope.idempotencyKey(), envelope.operationId());
    }
    long pendingCount();
    void markPublished(String operationId);
    void markPublishFailed(String operationId, String reason);
}
