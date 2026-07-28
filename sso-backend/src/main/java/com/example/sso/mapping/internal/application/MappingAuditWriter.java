package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Writes the audit rows a mapping re-evaluation produced, once its transaction has committed — see
 * {@link MappingAuditPending} for why they cannot be written inline.
 *
 * <p>Synchronous on purpose (no {@code @Async}): the rows carry their own tier explicitly, but the enrichment
 * inside {@code AuditService.record} still reads the ambient request, and handing them to a pool thread would
 * lose it. They are a handful of small inserts on a path that has just done considerably more work.
 */
@Component
@Slf4j
@RequiredArgsConstructor
class MappingAuditWriter {

    private final AuditService audit;

    /**
     * {@code fallbackExecution} deliberately on. Without it, an event published with no transaction in
     * progress is DISCARDED silently — a re-evaluation reached from an untransacted path would lose its whole
     * trail and nothing would say so. No transaction also means no rollback to outlive, which is the only
     * thing the deferral was protecting against, so handling it immediately is exactly right there.
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onCommitted(MappingAuditPending pending) {
        try {
            audit.record(pending.record());
        } catch (RuntimeException e) {
            // Swallowed on purpose, and this is the whole reason the method has a body. An exception thrown
            // from an AFTER_COMMIT callback aborts the REMAINING synchronizations — which on the profile-move
            // path includes UserAccessChangedEvent, i.e. the session termination. A failed audit row would
            // then leave a retracted role with its sessions still alive, while the caller sees a 500 that
            // reads as "the move failed". The transaction has already committed; nothing here can undo it,
            // so the only correct move is to lose the row loudly and let the security listeners run.
            log.error("Failed to record a mapping audit row after commit ({})", pending.record().type(), e);
        }
    }
}
