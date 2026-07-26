package com.example.sso.admin.internal.deny.application;

import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditType;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.deny.DenySpec;
import com.example.sso.user.deny.DenySubjectKind;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin orchestration for negative permissions: delegates the authorization, the last-admin guard, the write and
 * the session termination to the {@code user} module's {@link DenyService}, and audits the successful change.
 * A refused (403) or bricking (409) attempt throws before the audit line, so only effective changes are logged.
 *
 * <p>Monitoring gap (accepted, follow-up): the coarse {@code @CanManageDenies} URL denial IS audited (the
 * method-security {@code AuthorizationDeniedEvent} listener), but the AUTHORITATIVE per-subject refusal is a
 * domain {@code ForbiddenException} thrown inside {@link DenyService}, and the last-admin brick a
 * {@code ConflictException} — neither is recorded. Auditing them means writing the trail OUTSIDE this rolled-back
 * transaction (a {@code REQUIRES_NEW} record), so a per-subject probe leaves a trail; deferred.
 */
@Service
@RequiredArgsConstructor
public class DenyAdminService {

    private final DenyService denyService;
    private final AdminAuditLogger auditLogger;

    @Transactional
    public UUID create(DenySpec spec) {
        UUID id = denyService.create(spec); // authz + last-admin guard + insert + session termination
        auditLogger.log(AuditType.PERMISSION_DENY_CREATED,
                "deny kind=" + spec.kind() + " subject=" + spec.subjectId() + " pattern=" + spec.pattern());
        return id;
    }

    @Transactional
    public void lift(UUID denyId, DenySubjectKind kind) {
        denyService.lift(denyId, kind);
        auditLogger.log(AuditType.PERMISSION_DENY_LIFTED, "deny id=" + denyId + " kind=" + kind);
    }
}
