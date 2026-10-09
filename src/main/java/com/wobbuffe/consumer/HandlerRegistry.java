package com.wobbuffe.consumer;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HandlerRegistry {
    private final Map<String, OperationHandler> handlers = new ConcurrentHashMap<>();

    public HandlerRegistry(Collection<OperationHandler> configuredHandlers) {
        for (OperationHandler handler : configuredHandlers) {
            String key = key(handler.operationType(), handler.integrationId());
            if (handlers.putIfAbsent(key, handler) != null) {
                throw new IllegalStateException("Duplicate operation handler for " + key);
            }
        }
    }

    public OperationHandler require(String operationType, String integrationId) {
        OperationHandler handler = handlers.get(key(operationType, integrationId));
        if (handler == null) throw new PermanentOperationException("UNSUPPORTED_OPERATION", "No handler is registered");
        return handler;
    }

    private static String key(String type, String integration) { return type + ":" + integration; }
}
