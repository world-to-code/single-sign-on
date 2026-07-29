package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.shared.retry.RetrySchedule;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * This subsystem's binding of the shared {@link RetrySchedule}: the schedule for the DURABLE
 * session-termination retry — the crash/long-outage backstop that runs over minutes, distinct from the fast
 * in-thread retry ({@code termination-retry.*}) that clears a momentary blip within the request. Read from
 * {@code sso.zerotrust.termination-retry.durable.*}.
 *
 * <p>It used to carry its own copy of the curve and the give-up arithmetic, cloned from {@code logoutretry}'s
 * {@code RetryBackoff} under the rule of three, with a note asking whoever added a third caller to extract
 * them. That happened; only the config namespace and the give-up wording are per-subsystem now.
 */
@Component
class SessionTerminationRetryBackoff {

    private final RetrySchedule schedule;

    SessionTerminationRetryBackoff(
            @Value("${sso.zerotrust.termination-retry.durable.initial-backoff}") Duration initialBackoff,
            @Value("${sso.zerotrust.termination-retry.durable.multiplier}") double multiplier,
            @Value("${sso.zerotrust.termination-retry.durable.randomization-factor}") double randomizationFactor,
            @Value("${sso.zerotrust.termination-retry.durable.max-attempts}") int maxAttempts) {
        this.schedule = RetrySchedule.of(initialBackoff, multiplier, randomizationFactor, maxAttempts);
    }

    /** Delay in millis before {@code attempt} (1-based: attempt 1 is the first durable retry after hand-off). */
    long nextDelayMillis(int attempt) {
        return schedule.nextDelayMillis(attempt);
    }

    /** Number of durable retry attempts after which a still-undelivered termination is abandoned + audited. */
    int maxAttempts() {
        return schedule.maxAttempts();
    }

    /** Upper bound on the wall-clock from hand-off to give-up. */
    Duration maxGiveUpHorizon() {
        return schedule.maxGiveUpHorizon();
    }

    /** Fails startup when the durable store would expire an entry before this schedule is done with it. */
    void requireOutlivedBy(Duration registryTtl) {
        schedule.requireOutlivedBy(registryTtl,
                "sso.zerotrust.termination-retry.durable.registry-ttl", "a revocation");
    }
}
