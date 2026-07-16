package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.tenancy.OrgContext;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Durability backstop for metadata-driven auto-mapping. The event-driven path ({@code AFTER_COMMIT @Async}) is
 * fire-and-forget, so a crash between the attribute commit and the reconcile silently loses the change. This
 * scheduled sweep re-drives a FULL reconcile of every rule in its own tier, converging any missed
 * materialize/retract. One node per tick wins a short Redis lock ({@code SET NX PX}) and does the work; the
 * others skip. Reconcile is idempotent (claim-first {@code ON CONFLICT} + per-rule lock), so an overlapping tick
 * is harmless.
 */
@Component
class MappingReconcileSweeper {

    private static final String LOCK_KEY = "mapping:reconcile:sweep:lock";

    private final Logger log = LoggerFactory.getLogger(MappingReconcileSweeper.class);
    private final StringRedisTemplate redis;
    private final MappingRuleRepository rules;
    private final MappingRuleEvaluator evaluator;
    private final OrgContext orgContext;
    private final Duration lockTtl;
    private final String nodeToken = UUID.randomUUID().toString();

    MappingReconcileSweeper(StringRedisTemplate redis, MappingRuleRepository rules,
            MappingRuleEvaluator evaluator, OrgContext orgContext,
            @Value("${sso.mapping.reconcile.sweep.lock-ttl}") Duration lockTtl) {
        this.redis = redis;
        this.rules = rules;
        this.evaluator = evaluator;
        this.orgContext = orgContext;
        this.lockTtl = lockTtl;
    }

    @Scheduled(fixedDelayString = "${sso.mapping.reconcile.sweep.interval}")
    void sweep() {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, nodeToken, lockTtl))) {
            return; // another node owns this tick; the lock frees by natural expiry (never delete another's lock)
        }
        reconcileAllTiers();
    }

    /** Reconcile every rule across all tenants, each in ITS OWN tier so RLS + the WITH CHECK stamp confine the
     *  membership writes. Stable id order matches the async path's lock acquisition, so no lock-order cycle forms. */
    void reconcileAllTiers() {
        List<MappingRule> all = orgContext.callAsPlatform(() -> rules.findAll().stream()
                .sorted(Comparator.comparing(MappingRule::getId)).toList());
        for (MappingRule rule : all) {
            try {
                Runnable reconcile = () -> evaluator.reevaluateRule(rule);
                if (rule.getOrgId() == null) {
                    orgContext.runAsPlatform(reconcile);
                } else {
                    orgContext.runInOrg(rule.getOrgId(), reconcile);
                }
            } catch (RuntimeException e) {
                log.warn("mapping reconcile sweep failed for rule {} — next tick will retry", rule.getId(), e);
            }
        }
    }
}
