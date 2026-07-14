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
    private final Duration maxGiveUpHorizon;

    RetryBackoff(
            @Value("${sso.logout.propagation.retry.initial-backoff}") Duration initialBackoff,
            @Value("${sso.logout.propagation.retry.multiplier}") double multiplier,
            @Value("${sso.logout.propagation.retry.randomization-factor}") double randomizationFactor,
            @Value("${sso.logout.propagation.retry.max-attempts}") int maxAttempts) {
        this.interval = IntervalFunction.ofExponentialRandomBackoff(
                initialBackoff.toMillis(), multiplier, randomizationFactor);
        this.maxAttempts = maxAttempts;
        // Sum the MAX-jittered delay of every scheduled retry (attempts 1..maxAttempts-1) for the upper bound.
        double totalMillis = 0;
        for (int attempt = 1; attempt < maxAttempts; attempt++) {
            double base = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1.0);
            totalMillis += base * (1.0 + randomizationFactor);
        }
        this.maxGiveUpHorizon = Duration.ofMillis((long) Math.ceil(totalMillis));
    }

    /** Delay in millis before {@code attempt} (1-based: attempt 1 is the first retry after the initial send). */
    long nextDelayMillis(int attempt) {
        return interval.apply(attempt);
    }

    /** Number of retry attempts after which a still-undelivered participant is abandoned. */
    int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Upper bound on the wall-clock from the first failure to give-up: the sum of the MAX-jittered delay of
     * every scheduled retry (attempts 1..maxAttempts-1). A retry entry must outlive this, or it would expire
     * from the store before it is either delivered or explicitly abandoned — a silently-lost logout.
     */
    Duration maxGiveUpHorizon() {
        return maxGiveUpHorizon;
    }
}
