package com.wobbuffe.core;

public sealed interface ResilienceResult<T> permits ResilienceResult.Completed, ResilienceResult.Queued {
    record Completed<T>(T value) implements ResilienceResult<T> {}
    record Queued<T>(String operationId) implements ResilienceResult<T> {}
}
