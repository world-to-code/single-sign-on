package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled driver of durable session-termination retries — the crash/long-outage backstop behind the fast
 * in-thread retry. Each tick, one node in the fleet wins a short Redis lock ({@code SET NX PX}) and re-drives
 * every termination that is due; other nodes skip the tick. A claimed entry is leased forward (a visibility
 * timeout) before its re-drive, so a crash mid-flight re-surfaces it later instead of losing it, and a
 * concurrent tick never double-drives it. A re-drive that still fails is re-scheduled at the next backoff step;
 * at the give-up cap it is audited ({@code SESSION_TERMINATION_FAILED}) and dropped, so "revocation eventually
 * completes, and giving up is bounded and observable" has one owner here.
 */
@Component
class SessionTerminationRetrySweeper {

    private static final String LOCK_KEY = "session:termination:retry:sweep:lock";

    private final Logger log = LoggerFactory.getLogger(SessionTerminationRetrySweeper.class);
    private final StringRedisTemplate redis;
    private final SessionTerminationRetryRegistry registry;
    private final SessionTerminationRedriver redriver;
    private final SessionTerminationRetryBackoff backoff;
    private final AuditService audit;
    private final Clock clock;
    private final Duration lockTtl;
    private final Duration processingLease;
    private final int batchSize;
    private final String nodeToken = UUID.randomUUID().toString();

    SessionTerminationRetrySweeper(StringRedisTemplate redis, SessionTerminationRetryRegistry registry,
            SessionTerminationRedriver redriver, SessionTerminationRetryBackoff backoff, AuditService audit,
            Clock clock,
            @Value("${sso.zerotrust.termination-retry.durable.lock-ttl}") Duration lockTtl,
            @Value("${sso.zerotrust.termination-retry.durable.processing-lease}") Duration processingLease,
            @Value("${sso.zerotrust.termination-retry.durable.sweep-batch-size}") int batchSize) {
        this.redis = redis;
        this.registry = registry;
        this.redriver = redriver;
        this.backoff = backoff;
        this.audit = audit;
        this.clock = clock;
        this.lockTtl = lockTtl;
        this.processingLease = processingLease;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${sso.zerotrust.termination-retry.durable.sweep-interval}")
    void sweep() {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, nodeToken, lockTtl))) {
            return; // another node owns this tick; the lock frees by natural expiry (never delete another's lock)
        }
        long now = clock.millis();
        long leaseUntil = now + processingLease.toMillis();
        for (String key : registry.due(now, batchSize)) {
            registry.lease(key, leaseUntil); // visibility timeout BEFORE the re-drive, so a crash re-surfaces it
            registry.pending(key).ifPresentOrElse(pending -> drive(key, pending), () -> registry.remove(key));
        }
    }

    private void drive(String key, SessionTerminationRetryRegistry.Pending pending) {
        try {
            redriver.redrive(pending.request()); // idempotent; drops only sessions that still exist
            registry.remove(key); // delivered (or nothing left to terminate) — stop tracking it
        } catch (RuntimeException e) {
            reschedule(key, pending, e);
        }
    }

    private void reschedule(String key, SessionTerminationRetryRegistry.Pending pending, RuntimeException cause) {
        int nextAttempt = pending.attempts() + 1;
        if (nextAttempt >= backoff.maxAttempts()) {
            giveUp(pending, cause);
            registry.remove(key);
            return;
        }
        long dueAt = clock.millis() + backoff.nextDelayMillis(nextAttempt);
        registry.schedule(pending.request(), nextAttempt, dueAt);
        log.warn("durable session termination re-drive failed (attempt {}), rescheduled: {}", nextAttempt,
                cause.getClass().getSimpleName());
    }

    private void giveUp(SessionTerminationRetryRegistry.Pending pending, RuntimeException cause) {
        SessionTerminationRequest request = pending.request();
        // Log the failure TYPE only, never the throwable — a wrapped store exception can carry the key (and thus
        // the username) in its message; the audit record already carries principal + org for correlation.
        log.error("durable session termination abandoned after {} attempts ({}) — the session may outlive the "
                + "access change until its TTL", backoff.maxAttempts(), cause.getClass().getSimpleName());
        audit.record(new AuditRecord(AuditType.SESSION_TERMINATION_FAILED, request.auditPrincipal(), false,
                "durable session termination abandoned: " + cause.getClass().getSimpleName(), null,
                request.orgId()));
    }
}
