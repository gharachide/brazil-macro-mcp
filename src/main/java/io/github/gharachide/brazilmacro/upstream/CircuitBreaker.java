package io.github.gharachide.brazilmacro.upstream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Minimal circuit breaker. After {@code failureThreshold} consecutive failed requests it opens
 * and rejects calls immediately for {@code openDuration}; then it lets one trial request through
 * (half-open) and closes again if that request succeeds.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt = Instant.EPOCH;
    private boolean trialInFlight;

    public CircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be at least 1");
        }
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    /** Returns true if a request may be sent now. */
    public synchronized boolean tryAcquire() {
        if (state == State.OPEN) {
            if (clock.instant().isBefore(openedAt.plus(openDuration))) {
                return false;
            }
            state = State.HALF_OPEN;
        }
        if (state == State.HALF_OPEN) {
            if (trialInFlight) {
                return false;
            }
            trialInFlight = true;
        }
        return true;
    }

    /** The upstream answered (even with a client error such as 404): it is reachable. */
    public synchronized void onSuccess() {
        consecutiveFailures = 0;
        trialInFlight = false;
        state = State.CLOSED;
    }

    /** The request failed after all retries. */
    public synchronized void onFailure() {
        trialInFlight = false;
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = clock.instant();
        }
    }

    /** The request was abandoned for a reason unrelated to the upstream (interruption). */
    public synchronized void onAbandoned() {
        trialInFlight = false;
    }

    public synchronized State state() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration))) {
            return State.HALF_OPEN;
        }
        return state;
    }
}
