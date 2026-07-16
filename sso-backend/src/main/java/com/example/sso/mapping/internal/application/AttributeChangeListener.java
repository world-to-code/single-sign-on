package com.example.sso.mapping.internal.application;

import com.example.sso.metadata.EntityAttributeChangedEvent;
import com.example.sso.metadata.EntityKind;
import com.example.sso.user.group.UserGroupService;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Re-evaluates mapping rules when metadata attributes change. A USER attribute change re-evaluates that user; a
 * GROUP attribute change re-evaluates the group's MEMBERS (they inherit the tag). Runs AFTER the write commits on
 * the bounded {@code mappingReconcileExecutor} (decoupled from the editing request); the actual re-evaluation,
 * tenant re-entry, retry, and sweep-fallback live in {@link MappingReconcileDispatcher} — the members are
 * resolved inside that tier so the lookup is RLS-scoped.
 */
@Component
@RequiredArgsConstructor
public class AttributeChangeListener {

    private final UserGroupService userGroups;
    private final MappingReconcileDispatcher dispatcher;

    @Async("mappingReconcileExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAttributeChanged(EntityAttributeChangedEvent event) {
        dispatcher.reevaluate(() -> affectedUsers(event), event.orgId(), event.kind() + ":" + event.entityId());
    }

    /** Who carries/inherits the changed attribute: a USER themselves, a GROUP's members; other kinds drive nothing.
     *  Resolved inside the tier (by the dispatcher) so a GROUP's member lookup is RLS-scoped. */
    private Set<UUID> affectedUsers(EntityAttributeChangedEvent event) {
        return switch (event.kind()) {
            case USER -> Set.of(UUID.fromString(event.entityId()));
            case GROUP -> userGroups.memberIdsOf(Set.of(UUID.fromString(event.entityId())));
            case APPLICATION, RESOURCE -> Set.of(); // not subjects of auto-mapping
        };
    }
}
