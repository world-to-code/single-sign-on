package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import io.github.resilience4j.core.IntervalFunction;
import java.time.Clock;
import java.time.Duration;
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
 * <p>Two layers of resilience: a fast in-thread jittered-exponential retry clears a momentary blip within the
 * request; if that is exhausted (or the failure is non-transient), the termination is handed to the DURABLE
 * sweep ({@link SessionTerminationRetryRegistry}) — persisted in Redis and re-driven across restarts until it
 * succeeds or the give-up cap is reached — and audited as {@code SESSION_TERMINATION_DEFERRED}. So a store
 * outage longer than the in-thread window, or a crash mid-retry, no longer loses the termination; it is
 * re-driven, not merely observed. This never propagates: a failed cleanup must not disrupt the committing
 * transaction's post-commit phase.
 */
@Component
class ResilientSessionTermination {

    private static final Logger log = LoggerFactory.getLogger(ResilientSessionTermination.class);

    private final AuditService audit;
    private final SessionTerminationRedriver redriver;
    private final SessionTerminationRetryRegistry registry;
    private final SessionTerminationRetryBackoff durableBackoff;
    private final Clock clock;
    private final IntervalFunction backoff;
    private final int maxAttempts;

    ResilientSessionTermination(AuditService audit, SessionTerminationRedriver redriver,
            SessionTerminationRetryRegistry registry, SessionTerminationRetryBackoff durableBackoff, Clock clock,
            @Value("${sso.zerotrust.termination-retry.initial-backoff}") Duration initialBackoff,
            @Value("${sso.zerotrust.termination-retry.multiplier}") double multiplier,
            @Value("${sso.zerotrust.termination-retry.randomization-factor}") double randomizationFactor,
            @Value("${sso.zerotrust.termination-retry.max-attempts}") int maxAttempts) {
        this.audit = audit;
        this.redriver = redriver;
        this.registry = registry;
        this.durableBackoff = durableBackoff;
        this.clock = clock;
        this.backoff = IntervalFunction.ofExponentialRandomBackoff(
                initialBackoff.toMillis(), multiplier, randomizationFactor);
        this.maxAttempts = maxAttempts;
    }

    /**
     * Terminate {@code request}'s target, retrying a transient store failure in-thread; if that is exhausted or
     * the failure is non-transient, hand the termination to the durable sweep and audit it — never throw.
     * Terminating is idempotent (re-driving drops only sessions that still exist), so both the in-thread retry
     * and the later durable re-drive are safe to repeat.
     */
    void run(SessionTerminationRequest request) {
        try {
            withRetry(() -> redriver.redrive(request));
        } catch (RuntimeException e) {
            deferToDurableSweep(request, e);
        }
    }

    private void deferToDurableSweep(SessionTerminationRequest request, RuntimeException cause) {
        try {
            long dueAt = clock.millis() + durableBackoff.nextDelayMillis(1);
            registry.schedule(request, 0, dueAt); // attempt 0: the durable sweep owns the give-up counting from here
        } catch (RuntimeException durableStoreUnavailable) {
            // Redis — the durable store — is itself down, which is the very reason the termination failed. The
            // failure must NOT become silent: audit it to the INDEPENDENT (Postgres) audit store as a hard
            // failure. Log first so a line survives even if the audit write also fails; the absolute session TTL
            // remains the backstop. Never rethrow — this runs in an AFTER_COMMIT phase that swallows exceptions.
            log.error("session termination failed after an access change and the durable retry store is also "
                    + "unavailable — the session may outlive the change until its TTL", cause);
            audit.record(new AuditRecord(AuditType.SESSION_TERMINATION_FAILED, request.auditPrincipal(), false,
                    "session termination failed, durable store unavailable: " + cause.getClass().getSimpleName(),
                    null, request.orgId()));
            return;
        }
        log.warn("in-thread session termination retries exhausted after an access change — handed to the durable "
                + "sweep to re-drive: {}", cause.getClass().getSimpleName());
        // Name the failure type so a transient store blip reads differently from a non-transient bug, without
        // logging any argument value (no PII).
        audit.record(new AuditRecord(AuditType.SESSION_TERMINATION_DEFERRED, request.auditPrincipal(), false,
                "session termination deferred to durable retry: " + cause.getClass().getSimpleName(), null,
                request.orgId()));
    }

    private void withRetry(IntSupplier termination) {
        for (int attempt = 1; ; attempt++) {
            try {
                termination.getAsInt();
                return;
            } catch (DataAccessException e) {
                if (attempt >= maxAttempts) {
                    throw e; // exhausted transient retries — hand off to the durable sweep
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
