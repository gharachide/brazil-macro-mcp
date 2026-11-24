package io.github.gharachide.brazilmacro.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gharachide.brazilmacro.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30), clock);

    @Test
    void opensAfterConsecutiveFailures() {
        breaker.onFailure();
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void allowsOneTrialAfterTheOpenPeriodAndClosesOnSuccess() {
        breaker.onFailure();
        breaker.onFailure();
        clock.advance(Duration.ofSeconds(31));

        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.tryAcquire()).as("only one trial at a time").isFalse();
        breaker.onSuccess();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void reopensWhenTheTrialFails() {
        breaker.onFailure();
        breaker.onFailure();
        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.onFailure();

        assertThat(breaker.tryAcquire()).isFalse();
    }
}
