package com.example.sso.admin.internal.user.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Records a profile switch once it has actually happened.
 *
 * <p>{@code AuditService} writes in its own transaction, which is right for a failed login — the record must
 * survive whatever failed. It is wrong for a destructive mutation: recording inline would commit the claim
 * "these attributes were deleted" and then a rollback would undo the deletion, leaving an audit trail
 * asserting something that never occurred. AFTER_COMMIT means the row exists exactly when the change does.
 */
@Component
@RequiredArgsConstructor
class ProfileSwitchAuditor {

    private final AuditService audit;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProfileSwitched(ProfileSwitched event) {
        // Actor and subject are different people, and the row has to say which is which — this is a
        // destructive, role-retracting operation, so a trail naming the victim as the one who did it is worse
        // than none. The organization is named for a related reason: a username is only unique WITHIN one, and
        // AuditService otherwise falls back to the ambient context, which holds only while this listener runs
        // on the committing thread.
        audit.record(new AuditRecord(AuditType.ATTRIBUTE_CHANGED, event.actor(), true,
                "profile=" + event.profileId() + " removed=" + String.join(",", event.removedKeys()), null,
                AuditSubjectType.USER, event.subject(), event.orgId()));
    }
}
