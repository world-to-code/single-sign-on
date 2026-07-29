package com.example.sso.logoutretry.internal;

import com.example.sso.shared.retry.RetrySchedule;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * This subsystem's binding of the shared {@link RetrySchedule}: the jittered-exponential schedule for durable
 * logout retries, read from {@code sso.logout.propagation.retry.*}.
 *
 * <p>A distinct type rather than a qualified {@code RetrySchedule} bean, deliberately. Two schedules exist and
 * five places inject one; a distinct type makes wiring the WRONG schedule a compile error instead of a
 * qualifier string that is only checked at startup, and these are timing controls on revocation.
 */
@Component
class RetryBackoff {

    private final RetrySchedule schedule;

    RetryBackoff(
            @Value("${sso.logout.propagation.retry.initial-backoff}") Duration initialBackoff,
            @Value("${sso.logout.propagation.retry.multiplier}") double multiplier,
            @Value("${sso.logout.propagation.retry.randomization-factor}") double randomizationFactor,
            @Value("${sso.logout.propagation.retry.max-attempts}") int maxAttempts) {
        this.schedule = RetrySchedule.of(initialBackoff, multiplier, randomizationFactor, maxAttempts);
    }

    /** Delay in millis before {@code attempt} (1-based: attempt 1 is the first retry after the initial send). */
    long nextDelayMillis(int attempt) {
        return schedule.nextDelayMillis(attempt);
    }

    /** Number of retry attempts after which a still-undelivered participant is abandoned. */
    int maxAttempts() {
        return schedule.maxAttempts();
    }

    /** Upper bound on the wall-clock from the first failure to give-up. */
    Duration maxGiveUpHorizon() {
        return schedule.maxGiveUpHorizon();
    }

    /** Fails startup when the retry store would expire an entry before this schedule is done with it. */
    void requireOutlivedBy(Duration registryTtl) {
        schedule.requireOutlivedBy(registryTtl, "sso.logout.propagation.retry.registry-ttl", "a logout");
    }
}
