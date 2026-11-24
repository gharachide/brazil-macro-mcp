package io.github.gharachide.brazilmacro.web;

import io.github.gharachide.brazilmacro.upstream.FocusClient;
import io.github.gharachide.brazilmacro.upstream.SgsClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the circuit breaker of each BCB host without calling it. The server stays UP while the
 * BCB is down: it still answers, with stale data flagged as such or with explicit errors.
 */
@Component("bcb")
public class UpstreamHealthIndicator implements HealthIndicator {

    private final SgsClient sgs;
    private final FocusClient focus;

    public UpstreamHealthIndicator(SgsClient sgs, FocusClient focus) {
        this.sgs = sgs;
        this.focus = focus;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("sgsCircuit", sgs.circuitState().name())
                .withDetail("focusCircuit", focus.circuitState().name())
                .build();
    }
}
