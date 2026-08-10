package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.export.AuditExportSettingsService;
import com.example.sso.audit.export.AuditExportTarget;
import com.example.sso.shared.retry.RetrySchedule;
import java.time.Duration;
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
 * their batches, so one shipper per tick is the simpler thing to reason about.
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
    private final Duration lag;
    private final int batchSize;
    private final Duration lockTtl;
    private final String nodeToken = UUID.randomUUID().toString();

    /** Consecutive failures against the current batch; reset by any success. */
    private int consecutiveFailures;

    AuditExportSweeper(StringRedisTemplate redis, AuditExportSettingsService settings, AuditExportReader reader,
            OcsfMapper mapper, AuditExportDelivery delivery, AuditService audit,
            @Value("${sso.audit.export.lag}") Duration lag,
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
        this.lag = lag;
        this.batchSize = batchSize;
        this.lockTtl = lockTtl;
        this.retries = RetrySchedule.of(initialBackoff, multiplier, randomizationFactor, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${sso.audit.export.interval}")
    void sweep() {
        // No collector configured, switched off, or no longer a valid destination: nothing to do, and the
        // cursor stays put so enabling it later ships the backlog rather than starting from now.
        AuditExportTarget target = settings.target().orElse(null);
        if (target == null) {
            return;
        }
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, nodeToken, lockTtl))) {
            return; // another node owns this tick; the lock frees by natural expiry
        }
        export(target);
    }

    void export(AuditExportTarget target) {
        AuditExportBatch batch = reader.nextBatch(lag, batchSize);
        if (batch.isEmpty()) {
            return;
        }
        List<Map<String, Object>> events = batch.events().stream().map(mapper::toOcsf).toList();
        try {
            delivery.send(target, events);
            reader.commitCursor(batch);   // ONLY now: an unacknowledged batch must be re-offered
            consecutiveFailures = 0;
        } catch (RuntimeException failed) {
            onFailure(batch, failed);
        }
    }

    /**
     * A failed tick. The batch is simply left for the next one — the cursor never moved — so retrying is the
     * default and needs no queue of its own. What is recorded is the moment retrying stops being plausible.
     */
    private void onFailure(AuditExportBatch batch, RuntimeException failure) {
        consecutiveFailures++;
        log.error("Audit export delivery failed ({} consecutive)", consecutiveFailures, failure);
        if (consecutiveFailures >= retries.maxAttempts()) {
            audit.record(new AuditRecord(AuditType.AUDIT_EXPORT_FAILED, "system:audit-export", false,
                    "events=" + batch.events().size() + " consecutiveFailures=" + consecutiveFailures,
                    null).withReason("export.giveUp"));
            // Reset so the next tick starts a fresh count rather than recording this every tick from now on —
            // the incident is the transition, and one row per tick would bury it in its own alarm.
            consecutiveFailures = 0;
        }
    }
}
