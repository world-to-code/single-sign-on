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
 * commits and on the {@code @Async} executor (decoupled from the editing request, mirroring
 * {@code TenantBaselineProvisioner}); it re-enters the change's tier ({@code orgId}) so the evaluator's reads
 * and membership writes are RLS-scoped to that tenant. A failure never touches the (committed) attribute edit;
 * it is logged with the user id (never attribute values — no PII in logs) and the reconcile is idempotent.
 */
@Component
@RequiredArgsConstructor
public class AttributeChangeListener {

    private static final Logger log = LoggerFactory.getLogger(AttributeChangeListener.class);

    private final MappingRuleEvaluator evaluator;
    private final OrgContext orgContext;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAttributeChanged(EntityAttributeChangedEvent event) {
        if (event.kind() != EntityKind.USER) {
            return; // only user attributes drive group auto-mapping today
        }
        UUID userId = UUID.fromString(event.entityId());
        try {
            Runnable reevaluate = () -> evaluator.reevaluateUser(userId);
            if (event.orgId() == null) {
                orgContext.runAsPlatform(reevaluate);
            } else {
                orgContext.runInOrg(event.orgId(), reevaluate);
            }
        } catch (Exception e) {
            log.error("Mapping-rule re-evaluation failed for user {} — reconcile is idempotent, safe to re-run",
                    userId, e);
        }
    }
}
