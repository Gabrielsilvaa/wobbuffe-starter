package com.wobbuffe.consumer;

import java.util.function.LongSupplier;

public final class TokenBucket {
    private final int capacity;
    private final LongSupplier nanoTime;
    private double tokens;
    private long lastRefill;

    public TokenBucket(int tokensPerSecond, LongSupplier nanoTime) {
        this.capacity = tokensPerSecond;
        this.tokens = tokensPerSecond;
        this.nanoTime = nanoTime;
        this.lastRefill = nanoTime.getAsLong();
    }

    synchronized boolean tryAcquire() {
        long now = nanoTime.getAsLong();
        long elapsed = Math.max(0, now - lastRefill);
        tokens = Math.min(capacity, tokens + elapsed * (capacity / 1_000_000_000d));
        lastRefill = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }

    public synchronized double availableTokens() { return tokens; }
}
