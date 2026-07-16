package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import io.github.resilience4j.core.IntervalFunction;
import java.time.Duration;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Runs a session termination triggered by an access change so it never fails SILENTLY. Revocation propagation is
 * a load-bearing zero-trust guarantee — when a user is disabled/locked/re-roled their live sessions are deleted
 * so a frozen {@code SecurityContext} can't keep acting on stale authority. But that deletion runs in an
 * {@code AFTER_COMMIT} listener, where a thrown exception is swallowed by Spring's transaction synchronization;
 * a transient session-store (Redis) blip there would leave the session alive, unlogged, until its TTL.
 *
 * <p>This retries a transient store failure over a short jittered-exponential backoff and, if it still fails (or
 * fails non-transiently), AUDITS {@code SESSION_TERMINATION_FAILED} + logs — turning an invisible lingering
 * session into an observable, alertable event (the session's absolute TTL remains the eventual backstop). It
 * never propagates: a failed cleanup must not disrupt the committing transaction's post-commit phase.
 */
@Component
class ResilientSessionTermination {

    private static final Logger log = LoggerFactory.getLogger(ResilientSessionTermination.class);

    private final AuditService audit;
    private final IntervalFunction backoff;
    private final int maxAttempts;

    ResilientSessionTermination(AuditService audit,
            @Value("${sso.zerotrust.termination-retry.initial-backoff}") Duration initialBackoff,
            @Value("${sso.zerotrust.termination-retry.multiplier}") double multiplier,
            @Value("${sso.zerotrust.termination-retry.randomization-factor}") double randomizationFactor,
            @Value("${sso.zerotrust.termination-retry.max-attempts}") int maxAttempts) {
        this.audit = audit;
        this.backoff = IntervalFunction.ofExponentialRandomBackoff(
                initialBackoff.toMillis(), multiplier, randomizationFactor);
        this.maxAttempts = maxAttempts;
    }

    /**
     * Run {@code termination} for {@code username} in {@code orgId}, retrying a transient store failure; on a hard
     * failure audit + log and RETURN (never throw). {@code termination} must be idempotent (re-terminating drops
     * only sessions that still exist).
     */
    void terminate(String username, UUID orgId, IntSupplier termination) {
        try {
            withRetry(termination);
        } catch (RuntimeException e) {
            log.error("session termination failed after an access change for user in org {} — the session may "
                    + "outlive the change until its TTL", orgId, e);
            audit.record(new AuditRecord(AuditType.SESSION_TERMINATION_FAILED, username, false,
                    "session termination failed after an access change", null, orgId));
        }
    }

    private void withRetry(IntSupplier termination) {
        for (int attempt = 1; ; attempt++) {
            try {
                termination.getAsInt();
                return;
            } catch (DataAccessException e) {
                if (attempt >= maxAttempts) {
                    throw e; // exhausted transient retries — the caller audits it
                }
                sleep(backoff.apply(attempt));
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("session termination retry interrupted", e);
        }
    }
}
