package com.example.sso.mapping.internal.application;

import com.example.sso.user.group.GroupMembershipChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Re-evaluates mapping rules when a group's membership changes: a user added to (or removed from) a group gains
 * (or loses) that group's inherited attributes, so their rules must be recomputed. Runs AFTER the change commits
 * on the same bounded executor as the attribute path; the retry, tenant re-entry, and sweep fallback live in
 * {@link MappingReconcileDispatcher}.
 */
@Component
@RequiredArgsConstructor
public class MembershipChangeListener {

    private final MappingReconcileDispatcher dispatcher;

    @Async("mappingReconcileExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMembershipChanged(GroupMembershipChangedEvent event) {
        dispatcher.reevaluate(event::userIds, event.orgId(), "membership");
    }
}
