package com.wobbuffe.core;

import com.wobbuffe.config.WobbuffeProperties;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

public final class AdaptiveRouter {
    private final WobbuffeProperties.Routing config;
    private final Clock clock;
    private final RandomGenerator random;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public AdaptiveRouter(WobbuffeProperties.Routing config, Clock clock, RandomGenerator random) {
        this.config = config; this.clock = clock; this.random = random;
    }

    public boolean shouldRoute(String integrationId, boolean degraded) {
        if (!config.isEnabled()) return false;
        State state = states.computeIfAbsent(integrationId, ignored -> new State());
        synchronized (state) {
            long now = clock.millis();
            if (state.lastEvaluation == 0 || now - state.lastEvaluation >= config.getEvaluationInterval().toMillis()) {
                state.lastEvaluation = now;
                if (degraded) {
                    state.healthyWindows = 0;
                    state.percent = Math.min(config.getMaxPercent(), state.percent == 0 ? config.getStepPercent() : state.percent + config.getStepPercent());
                } else if (state.percent > 0 && ++state.healthyWindows >= 2) {
                    state.percent = Math.max(0, state.percent - config.getStepPercent());
                    state.healthyWindows = 0;
                }
            }
            return degraded && state.percent > 0 && random.nextInt(100) < state.percent;
        }
    }

    public int currentPercent(String integrationId) {
        State state = states.get(integrationId);
        if (state == null) return 0;
        synchronized (state) { return state.percent; }
    }

    private static final class State { private int percent; private int healthyWindows; private long lastEvaluation; }
}
