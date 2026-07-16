package com.example.sso.mapping.internal.application;

import com.example.sso.tenancy.OrgContext;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Re-evaluates a set of users' mapping rules inside their tier, shared by the attribute- and membership-change
 * listeners so the retry, tenant re-entry, and sweep-fallback behaviour stay identical. Transient lock contention
 * is retried in-thread ({@link ReconcileRetry}); a persistent failure never touches the (committed) change that
 * triggered it — it is logged (a cause label + org, never attribute values, no PII) and re-driven by the
 * scheduled reconcile sweep, since a fire-and-forget {@code AFTER_COMMIT} event has no re-delivery of its own.
 */
@Component
@RequiredArgsConstructor
class MappingReconcileDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MappingReconcileDispatcher.class);

    private final MappingRuleEvaluator evaluator;
    private final OrgContext orgContext;
    private final ReconcileRetry retry;

    /** Re-evaluate each affected user's rules in {@code orgId}'s tier ({@code null} = platform), retried in-thread.
     *  The affected-user set is resolved by {@code affectedUsers} INSIDE the tier, so a lookup it makes (e.g. a
     *  group's members) is RLS-scoped to that tenant. */
    void reevaluate(Supplier<Set<UUID>> affectedUsers, UUID orgId, String cause) {
        Runnable work = () -> inTier(orgId, () -> affectedUsers.get().forEach(evaluator::reevaluateUser));
        try {
            retry.run(work);
        } catch (Exception e) {
            log.error("Mapping re-evaluation failed for {} (org {}) — the reconcile sweep will re-drive it",
                    cause, orgId, e);
        }
    }

    private void inTier(UUID orgId, Runnable work) {
        if (orgId == null) {
            orgContext.runAsPlatform(work);
        } else {
            orgContext.runInOrg(orgId, work);
        }
    }
}
