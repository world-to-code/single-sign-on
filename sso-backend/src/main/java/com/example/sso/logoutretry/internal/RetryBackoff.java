package com.example.sso.logoutretry.internal;

import io.github.resilience4j.core.IntervalFunction;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The jittered-exponential backoff schedule for durable logout retries, delegating to resilience4j's
 * {@link IntervalFunction} rather than hand-rolling the curve (or the jitter). Attempt N's delay is
 * {@code initial · multiplier^(N-1)} spread over a ±{@code randomizationFactor} band, so a fleet of nodes
 * re-driving the same RP outage does not thundering-herd it. Also owns the give-up cap.
 */
@Component
class RetryBackoff {

    private final IntervalFunction interval;
    private final int maxAttempts;

    RetryBackoff(
            @Value("${sso.logout.propagation.retry.initial-backoff}") Duration initialBackoff,
            @Value("${sso.logout.propagation.retry.multiplier}") double multiplier,
            @Value("${sso.logout.propagation.retry.randomization-factor}") double randomizationFactor,
            @Value("${sso.logout.propagation.retry.max-attempts}") int maxAttempts) {
        this.interval = IntervalFunction.ofExponentialRandomBackoff(
                initialBackoff.toMillis(), multiplier, randomizationFactor);
        this.maxAttempts = maxAttempts;
    }

    /** Delay in millis before {@code attempt} (1-based: attempt 1 is the first retry after the initial send). */
    long nextDelayMillis(int attempt) {
        return interval.apply(attempt);
    }

    /** Number of retry attempts after which a still-undelivered participant is abandoned. */
    int maxAttempts() {
        return maxAttempts;
    }
}
