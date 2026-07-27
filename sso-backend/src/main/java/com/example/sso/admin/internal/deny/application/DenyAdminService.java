package com.example.sso.admin.internal.deny.application;

import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditType;
import com.example.sso.shared.error.ApiException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.deny.DenySpec;
import com.example.sso.user.deny.DenySubjectKind;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin orchestration for negative permissions: delegates the authorization, the last-admin guard, the write and
 * the session termination to the {@code user} module's {@link DenyService}, and audits the outcome either way.
 *
 * <p>Both refusal shapes are recorded, not just the successful change: the coarse {@code @CanManageDenies} URL
 * denial is audited by the method-security listener, but the AUTHORITATIVE per-subject decision is a domain
 * {@code ForbiddenException} thrown inside {@link DenyService} (and the last-admin brick a
 * {@code ConflictException}). Without a line here, probing WHICH subjects an actor may withhold permissions from
 * — and which tier a deny would brick — is invisible. The exception is rethrown unchanged; only the trail is
 * added, and it survives the refusal's rollback because the audit writer commits in its own transaction.
 */
@Service
@RequiredArgsConstructor
public class DenyAdminService {

    private final DenyService denyService;
    private final AdminAuditLogger auditLogger;

    @Transactional
    public UUID create(DenySpec spec) {
        String detail = "deny kind=" + spec.kind() + " subject=" + spec.subjectId() + " pattern=" + spec.pattern();
        try {
            UUID id = denyService.create(spec); // authz + last-admin guard + insert + session termination
            auditLogger.log(AuditType.PERMISSION_DENY_CREATED, detail);
            return id;
        } catch (ForbiddenException | ConflictException refused) {
            auditRefusal(AuditType.PERMISSION_DENY_CREATED, detail, refused);
            throw refused;
        }
    }

    @Transactional
    public void lift(UUID denyId, DenySubjectKind kind) {
        String detail = "deny id=" + denyId + " kind=" + kind;
        try {
            denyService.lift(denyId, kind);
            auditLogger.log(AuditType.PERMISSION_DENY_LIFTED, detail);
        } catch (ForbiddenException refused) {
            auditRefusal(AuditType.PERMISSION_DENY_LIFTED, detail, refused);
            throw refused;
        }
    }

    /**
     * Writes the refusal to the trail without ever replacing it. The audit write borrows a second connection for
     * its own transaction while this one is still open, so it can fail on pool exhaustion — and a failure there
     * must not turn the caller's 403/409 into a 500. The audit failure is attached to the refusal instead.
     */
    private void auditRefusal(AuditType type, String detail, ApiException refused) {
        try {
            auditLogger.logFailure(type, detail, refused.getMessageKey());
        } catch (RuntimeException auditFailed) {
            refused.addSuppressed(auditFailed);
        }
    }
}
