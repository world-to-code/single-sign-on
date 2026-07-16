package com.example.sso.session.internal.lifecycle.application;

import io.github.resilience4j.core.IntervalFunction;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The jittered-exponential schedule for the DURABLE session-termination retry — the crash/long-outage backstop
 * that runs over minutes, distinct from the fast in-thread retry ({@code termination-retry.*}) that clears a
 * momentary blip within the request. Delegates the curve and jitter to resilience4j's {@link IntervalFunction}
 * rather than hand-rolling them, and owns the give-up cap so "revocation eventually completes, and giving up is
 * bounded and audited" has one owner.
 *
 * <p>The curve + give-up-horizon math mirrors {@code logoutretry}'s {@code RetryBackoff} (a second durable-retry
 * subsystem). Kept a deliberate clone under the rule of three — a THIRD durable-retry use should extract the
 * shared schedule into one primitive taking its config namespace; until then, a fix here must be mirrored there.
 */
@Component
class SessionTerminationRetryBackoff {

    private final IntervalFunction interval;
    private final int maxAttempts;
    private final Duration maxGiveUpHorizon;

    SessionTerminationRetryBackoff(
            @Value("${sso.zerotrust.termination-retry.durable.initial-backoff}") Duration initialBackoff,
            @Value("${sso.zerotrust.termination-retry.durable.multiplier}") double multiplier,
            @Value("${sso.zerotrust.termination-retry.durable.randomization-factor}") double randomizationFactor,
            @Value("${sso.zerotrust.termination-retry.durable.max-attempts}") int maxAttempts) {
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

    /** Delay in millis before {@code attempt} (1-based: attempt 1 is the first durable retry after hand-off). */
    long nextDelayMillis(int attempt) {
        return interval.apply(attempt);
    }

    /** Number of durable retry attempts after which a still-undelivered termination is abandoned + audited. */
    int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Upper bound on the wall-clock from hand-off to give-up: the sum of the MAX-jittered delay of every
     * scheduled retry. A durable entry's TTL must outlive this, or it would expire from Redis before it is
     * either delivered or explicitly abandoned — a silently-lost termination.
     */
    Duration maxGiveUpHorizon() {
        return maxGiveUpHorizon;
    }
}
