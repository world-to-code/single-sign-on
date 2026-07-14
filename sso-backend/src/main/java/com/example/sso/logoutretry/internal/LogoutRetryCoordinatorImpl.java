package com.example.sso.logoutretry.internal;

import com.example.sso.logoutretry.LogoutRetryCoordinator;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Drives the retry state machine after each propagation pass. Nothing left to deliver → drop the entry.
 * Otherwise advance the attempt count: below the cap, re-schedule at the next backoff step; at the cap, run
 * the caller's give-up hook (audit the abandoned participants) and drop the entry. This is the ONLY place the
 * cap is applied, so the "retries are bounded and giving up is observable" guarantee has one owner.
 */
@Service
class LogoutRetryCoordinatorImpl implements LogoutRetryCoordinator {

    private final DurableRetryRegistry registry;
    private final RetryBackoff backoff;
    private final Clock clock;

    LogoutRetryCoordinatorImpl(DurableRetryRegistry registry, RetryBackoff backoff, Clock clock) {
        this.registry = registry;
        this.backoff = backoff;
        this.clock = clock;
    }

    @Override
    public void reschedule(String queue, String sid, String username, boolean hasRemaining, Runnable onGiveUp) {
        if (!hasRemaining) {
            registry.remove(queue, sid); // fully delivered (or nothing was retryable): stop tracking it
            return;
        }
        int nextAttempt = registry.meta(queue, sid).map(DurableRetryRegistry.Meta::attempts).orElse(0) + 1;
        if (nextAttempt >= backoff.maxAttempts()) {
            onGiveUp.run(); // abandoned: the caller audits the still-undelivered participants and clears the index
            registry.remove(queue, sid);
            return;
        }
        long dueAt = clock.millis() + backoff.nextDelayMillis(nextAttempt);
        registry.schedule(queue, sid, nextAttempt, username, dueAt);
    }
}
