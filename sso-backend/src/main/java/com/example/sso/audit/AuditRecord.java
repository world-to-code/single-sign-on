package com.example.sso.audit;

import java.util.UUID;

/**
 * Immutable parameter object for {@link AuditService#record(AuditRecord)}: a single security-relevant
 * event — its type, the acting principal, the outcome, optional free-form detail and remote IP, the
 * scopeable subject it acts upon, and the tenant ({@code orgId}) it occurred in. Callers that know the
 * tenant (the login flow) set {@code orgId} explicitly; otherwise the audit service defaults it from the
 * current tenant context. Convenience constructors default the subject to {@link AuditSubjectType#NONE}
 * and the org to null.
 */
public record AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp,
                          AuditSubjectType subjectType, String subjectId, UUID orgId) {

    public AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp) {
        this(type, principal, success, detail, remoteIp, AuditSubjectType.NONE, null, null);
    }

    public AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp,
                       AuditSubjectType subjectType, String subjectId) {
        this(type, principal, success, detail, remoteIp, subjectType, subjectId, null);
    }

    /** For a tenant-known event with no scopeable subject (the login flow tags its resolved org). */
    public AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp,
                       UUID orgId) {
        this(type, principal, success, detail, remoteIp, AuditSubjectType.NONE, null, orgId);
    }
}
