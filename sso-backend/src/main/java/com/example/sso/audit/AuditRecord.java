package com.example.sso.audit;

import java.util.UUID;

/**
 * Immutable parameter object for {@link AuditService#record(AuditRecord)}: a single security-relevant
 * event — its type, the acting principal, the outcome, optional free-form detail and remote IP, the
 * scopeable subject it acts upon, the tenant ({@code orgId}) it occurred in, and an optional structured
 * {@code reason} (a machine-friendly outcome code/phrase, distinct from the free-form detail). Callers
 * that know the tenant (the login flow) set {@code orgId} explicitly; otherwise the audit service
 * defaults it from the current tenant context. The acting actor's identity, client/device, correlation
 * id, and triage severity are NOT set here — the audit service enriches them centrally. Convenience
 * constructors default the subject to {@link AuditSubjectType#NONE}, the org to null, and the reason to null.
 */
public record AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp,
                          AuditSubjectType subjectType, String subjectId, UUID orgId, String reason) {

    public AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp) {
        this(type, principal, success, detail, remoteIp, AuditSubjectType.NONE, null, null, null);
    }

    public AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp,
                       AuditSubjectType subjectType, String subjectId) {
        this(type, principal, success, detail, remoteIp, subjectType, subjectId, null, null);
    }

    public AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp,
                       AuditSubjectType subjectType, String subjectId, UUID orgId) {
        this(type, principal, success, detail, remoteIp, subjectType, subjectId, orgId, null);
    }

    /** For a tenant-known event with no scopeable subject (the login flow tags its resolved org). */
    public AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp,
                       UUID orgId) {
        this(type, principal, success, detail, remoteIp, AuditSubjectType.NONE, null, orgId, null);
    }

    /** Returns a copy carrying the structured outcome reason (used at failure/denial sites). */
    public AuditRecord withReason(String reason) {
        return new AuditRecord(type, principal, success, detail, remoteIp, subjectType, subjectId, orgId, reason);
    }
}
