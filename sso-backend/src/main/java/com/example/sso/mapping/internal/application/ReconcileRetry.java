package com.example.sso.mapping.internal.application;

import io.github.resilience4j.core.IntervalFunction;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/**
 * A tight in-thread retry for the async reconcile, covering ONLY transient lock contention (deadlock /
 * lock-timeout / serialization) introduced by the per-rule pessimistic lock — the loser of a lock race is
 * re-driven a few times over a jittered-exponential backoff (resilience4j {@link IntervalFunction}, mirroring
 * {@code RetryBackoff}) instead of failing outright. Non-transient failures propagate unchanged; anything that
 * outlives the small cap is recovered by the scheduled full-reconcile sweep. The backoff is short by design so a
 * bounded pool thread is not held for long.
 */
@Component
class ReconcileRetry {

    private final IntervalFunction interval;
    private final int maxAttempts;

    ReconcileRetry(
            @Value("${sso.mapping.reconcile.retry.initial-backoff}") Duration initialBackoff,
            @Value("${sso.mapping.reconcile.retry.multiplier}") double multiplier,
            @Value("${sso.mapping.reconcile.retry.randomization-factor}") double randomizationFactor,
            @Value("${sso.mapping.reconcile.retry.max-attempts}") int maxAttempts) {
        this.interval = IntervalFunction.ofExponentialRandomBackoff(
                initialBackoff.toMillis(), multiplier, randomizationFactor);
        this.maxAttempts = maxAttempts;
    }

    /** Run {@code action}, retrying only transient lock-contention failures up to the cap; other errors propagate. */
    void run(Runnable action) {
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                return;
            } catch (TransientDataAccessException e) {
                if (attempt >= maxAttempts) {
                    throw e; // give up in-thread; the scheduled sweep is the durable net
                }
                sleep(interval.apply(attempt));
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reconcile retry interrupted", e);
        }
    }
}
