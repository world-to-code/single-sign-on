package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.mapping.internal.domain.MappingRule;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Every audit row a mapping re-evaluation writes, and — the reason this is a type rather than three private
 * methods — the decision of which of them survives a rollback.
 *
 * <p>There are exactly two answers and they are opposites, so the method NAMES carry the difference:
 *
 * <ul>
 *   <li>{@code change…} rows are published and written after commit ({@link MappingAuditPending}). A
 *       re-evaluation can be rejected after it has already retracted fifty memberships, and
 *       {@code AuditEventWriter} commits in its own transaction — so written inline they outlived the rollback
 *       and the trail asserted authority moves that were undone.</li>
 *   <li>{@link #refusalNow} is written inline, because that one has to outlive the rollback it describes. On
 *       the asynchronous path the exception is swallowed by the executor and the sweep re-drives the same
 *       rejected transaction, so without this row an operator sees a rule that has silently stopped converging
 *       with nothing anywhere to say why.</li>
 * </ul>
 *
 * <p>Getting that choice wrong is invisible until an investigator reads the trail, which is exactly when it
 * matters — so it lives in one place with the reasoning attached, instead of in a comment beside each call.
 */
@Component
@RequiredArgsConstructor
class MappingAuditTrail {

    private static final String SYSTEM_PRINCIPAL = "system:mapping-rule";

    private final ApplicationEventPublisher events;
    private final AuditService audit;

    /** A membership this rule granted or retracted for one user. Dropped if the transaction rolls back. */
    void changedMembership(AuditType type, MappingRule rule, UUID userId) {
        String detail = "rule %s (%s): user %s / target %s"
                .formatted(rule.getId(), rule.getThenKind(), userId, rule.getTargetId());
        publishOnCommit(new AuditRecord(type, SYSTEM_PRINCIPAL, true, detail, null,
                AuditSubjectType.USER, userId.toString(), rule.getOrgId()));
    }

    /** A rule-level outcome with no per-user subject: the author/source re-validation for the whole grant. */
    void changedGrantAdmission(AuditType type, MappingRule rule) {
        String detail = "rule %s (%s): target %s / author %s"
                .formatted(rule.getId(), rule.getThenKind(), rule.getTargetId(), rule.getCreatedBy());
        publishOnCommit(new AuditRecord(type, SYSTEM_PRINCIPAL, true, detail, null,
                AuditSubjectType.NONE, rule.getTargetId().toString(), rule.getOrgId()));
    }

    /** The re-evaluation was rejected and everything above it is being rolled back. Written NOW, and kept. */
    void refusalNow(UUID tier, int retractedTargets, RuntimeException refused) {
        String detail = ("re-evaluation rolled back in tier %s: retracting %d target(s) "
                + "would have left it with no administrator").formatted(tier, retractedTargets);
        // getMessage() is the exception's message KEY, not anything a caller supplied.
        audit.record(new AuditRecord(AuditType.MAPPING_RULE_RETRACTION_REFUSED, SYSTEM_PRINCIPAL, false, detail,
                null, AuditSubjectType.NONE, null, tier).withReason(refused.getMessage()));
    }

    /**
     * A rule has failed to reconcile often enough to be considered stuck, and the sweep has parked it at its
     * backoff cap. Written NOW like a refusal — there is no business transaction here at all, and this row is
     * the ONE signal an operator gets: after it, the rule goes quiet rather than repeating itself hourly.
     */
    void reconcileStalledNow(MappingRule rule) {
        String detail = "rule %s (%s): target %s has stopped converging — the sweep is now deferring it"
                .formatted(rule.getId(), rule.getThenKind(), rule.getTargetId());
        audit.record(new AuditRecord(AuditType.MAPPING_RULE_RECONCILE_STALLED, SYSTEM_PRINCIPAL, false, detail,
                null, AuditSubjectType.NONE, rule.getTargetId().toString(), rule.getOrgId()));
    }

    private void publishOnCommit(AuditRecord record) {
        events.publishEvent(new MappingAuditPending(record));
    }
}
