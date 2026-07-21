package com.example.sso.admin.internal.user.application;

import com.example.sso.audit.AuditRecord;
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
        audit.record(new AuditRecord(AuditType.ATTRIBUTE_CHANGED, event.username(), true,
                "profile=" + event.profileId() + " removed=" + String.join(",", event.removedKeys()), null));
    }
}
