package com.example.sso.mapping.internal.application;

import com.example.sso.metadata.EntityAttributeChangedEvent;
import com.example.sso.metadata.EntityKind;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Re-evaluates a user's mapping rules when their metadata attributes change. Runs AFTER the attribute write
 * commits and on the dedicated bounded {@code mappingReconcileExecutor} (decoupled from the editing request,
 * off the shared/unbounded default pool); it re-enters the change's tier ({@code orgId}) so the evaluator's
 * reads and membership writes are RLS-scoped to that tenant. Transient lock contention is retried in-thread
 * ({@link ReconcileRetry}); anything that still fails never touches the (committed) attribute edit — it is
 * logged with the user id (never attribute values — no PII) and re-driven by the scheduled reconcile sweep,
 * since a fire-and-forget {@code AFTER_COMMIT} event has no re-delivery of its own.
 */
@Component
@RequiredArgsConstructor
public class AttributeChangeListener {

    private static final Logger log = LoggerFactory.getLogger(AttributeChangeListener.class);

    private final MappingRuleEvaluator evaluator;
    private final OrgContext orgContext;
    private final ReconcileRetry retry;

    @Async("mappingReconcileExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAttributeChanged(EntityAttributeChangedEvent event) {
        if (event.kind() != EntityKind.USER) {
            return; // only user attributes drive auto-mapping today
        }
        UUID userId = UUID.fromString(event.entityId());
        Runnable reevaluate = () -> {
            if (event.orgId() == null) {
                orgContext.runAsPlatform(() -> evaluator.reevaluateUser(userId));
            } else {
                orgContext.runInOrg(event.orgId(), () -> evaluator.reevaluateUser(userId));
            }
        };
        try {
            retry.run(reevaluate);
        } catch (Exception e) {
            log.error("Mapping-rule re-evaluation failed for user {} — the scheduled reconcile sweep will re-drive it",
                    userId, e);
        }
    }
}
