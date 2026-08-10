package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.export.AuditExportSettingsService;
import com.example.sso.audit.export.AuditExportTarget;
import com.example.sso.shared.retry.RetrySchedule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ships the audit trail to the collector, one node per tick.
 *
 * <p>The lock is the {@code SET NX PX} pattern the other sweepers use — a node token, released by natural
 * expiry, never deleting another node's lock. Two nodes exporting at once would not corrupt anything (the
 * collector deduplicates on the record id), but they would both advance the same cursor and could interleave
 * their batches, so one shipper per tick is the simpler thing to reason about. Its TTL must stay SHORTER than
 * the interval: a lock outliving the tick that took it silently becomes the real cadence, and with a capped
 * batch that caps throughput rather than protecting a long run — which is what a longer TTL does for the
 * sweepers this pattern was copied from.
 *
 * <p><b>The cursor advances only after the collector has acknowledged the batch.</b> Everything about this
 * design serves that: a batch that fails is retried on the next tick from the same position, so a failed POST
 * costs latency rather than events.
 *
 * <p><b>On give-up the failure is recorded LOCALLY.</b> The place that would normally be told is the collector
 * that is not receiving anything — the same reasoning that makes session termination audit to Postgres when
 * Redis is what failed. An export stalled for an hour is a security incident rather than a background
 * nuisance: it is the state an attacker arranges before doing anything else, so the row is CRITICAL and
 * reaches the console's own feed, which is the one place still working.
 */
@Component
class AuditExportSweeper {

    private static final String LOCK_KEY = "sso:audit:export:lock";

    private final Logger log = LoggerFactory.getLogger(AuditExportSweeper.class);
    private final StringRedisTemplate redis;
    private final AuditExportSettingsService settings;
    private final AuditExportReader reader;
    private final OcsfMapper mapper;
    private final AuditExportDelivery delivery;
    private final AuditService audit;
    private final RetrySchedule retries;
    private final Clock clock;
    private final Duration lag;
    private final Duration runBudget;
    private final int batchSize;
    private final Duration lockTtl;
    private final String nodeToken = UUID.randomUUID().toString();

    /** Consecutive failures against the current batch; reset by any success. */
    private int consecutiveFailures;

    AuditExportSweeper(StringRedisTemplate redis, AuditExportSettingsService settings, AuditExportReader reader,
            OcsfMapper mapper, AuditExportDelivery delivery, AuditService audit, Clock clock,
            @Value("${sso.audit.export.lag}") Duration lag,
            @Value("${sso.audit.export.run-budget}") Duration runBudget,
            @Value("${sso.audit.export.batch-size}") int batchSize,
            @Value("${sso.audit.export.lock-ttl}") Duration lockTtl,
            @Value("${sso.audit.export.retry.initial-backoff}") Duration initialBackoff,
            @Value("${sso.audit.export.retry.multiplier}") double multiplier,
            @Value("${sso.audit.export.retry.randomization-factor}") double randomizationFactor,
            @Value("${sso.audit.export.retry.max-attempts}") int maxAttempts) {
        this.redis = redis;
        this.settings = settings;
        this.reader = reader;
        this.mapper = mapper;
        this.delivery = delivery;
        this.audit = audit;
        this.clock = clock;
        this.lag = lag;
        this.runBudget = runBudget;
        this.batchSize = batchSize;
        this.lockTtl = lockTtl;
        this.retries = RetrySchedule.of(initialBackoff, multiplier, randomizationFactor, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${sso.audit.export.interval}")
    void sweep() {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, nodeToken, lockTtl))) {
            return; // another node owns this tick; the lock frees by natural expiry
        }
        export();
    }

    /**
     * One tick's work, with EVERY step inside the same failure accounting.
     *
     * <p>That is the correction this method exists in this shape for. Only the POST used to be guarded, so an
     * export could stop for any other reason — an unresolvable collector, a credential this instance can no
     * longer decrypt, a corrupt cursor, a row whose type no longer maps — and stop in silence, because those
     * threw straight out of the scheduled method where the alarm could not see them. Every one of those is the
     * same event to whoever is relying on the trail: it is not arriving.
     */
    void export() {
        try {
            // Empty means nobody asked for an export. The cursor stays put, so enabling it later ships the
            // backlog rather than starting from now.
            AuditExportTarget target = settings.target().orElse(null);
            if (target == null) {
                return;
            }
            drainWithin(target, clock.instant().plus(runBudget));
            consecutiveFailures = 0;
        } catch (RuntimeException failed) {
            onFailure(failed);
        }
    }

    /**
     * Ships batches until it runs out of events or out of time.
     *
     * <p>One batch per tick made the configuration, not the collector, the throughput ceiling: 500 rows per
     * lock window, whatever the deployment actually produces. Above that rate the backlog only grows, and
     * every indicator stays green while it does — each POST succeeds, the cursor advances, and the collector
     * receives a steady stream that happens to describe hours ago. A SIEM confidently reporting the past is
     * worse than one that is visibly down, because nobody goes looking.
     *
     * <p>The budget is what keeps this honest against the lock: it must expire well before the lock does, so a
     * long drain can never overlap the next node's tick.
     */
    private void drainWithin(AuditExportTarget target, Instant deadline) {
        while (shipOneBatch(target) && clock.instant().isBefore(deadline)) {
            // a full batch means more is probably waiting; keep the lock working rather than sleeping on it
        }
    }

    /** @return whether the batch came back full, i.e. there is more behind it. */
    private boolean shipOneBatch(AuditExportTarget target) {
        AuditExportBatch batch = reader.nextBatch(lag, batchSize);
        if (batch.isEmpty()) {
            return false;
        }
        List<Map<String, Object>> events = batch.events().stream().map(mapper::toOcsf).toList();
        delivery.send(target, events);
        reader.commitCursor(batch);   // ONLY now: an unacknowledged batch must be re-offered
        return batch.events().size() == batchSize;
    }

    /**
     * A failed tick. The batch is simply left for the next one — the cursor never moved — so retrying is the
     * default and needs no queue of its own. What is recorded is the moment retrying stops being plausible.
     */
    private void onFailure(RuntimeException failure) {
        consecutiveFailures++;
        log.error("Audit export failed ({} consecutive)", consecutiveFailures, failure);
        if (consecutiveFailures >= retries.maxAttempts()) {
            // The exception TYPE, never its message: a collector's rejection body would otherwise be copied
            // into an audit row, and the batch it is rejecting is what that body tends to quote back.
            audit.record(new AuditRecord(AuditType.AUDIT_EXPORT_FAILED, "system:audit-export", false,
                    "cause=" + failure.getClass().getSimpleName() + " consecutiveFailures=" + consecutiveFailures,
                    null).withReason("export.giveUp"));
            // Reset so the next tick starts a fresh count rather than recording this every tick from now on —
            // the incident is the transition, and one row per tick would bury it in its own alarm.
            consecutiveFailures = 0;
        }
    }
}
