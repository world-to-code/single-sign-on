package com.example.sso.audit;

/**
 * Immutable parameter object for {@link AuditService#record(AuditRecord)}: a single security-relevant
 * event — its type, the acting principal, the outcome, optional free-form detail and remote IP, and the
 * scopeable subject it acts upon (defaults to {@link AuditSubjectType#NONE} via the convenience ctor).
 */
public record AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp,
                          AuditSubjectType subjectType, String subjectId) {

    public AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp) {
        this(type, principal, success, detail, remoteIp, AuditSubjectType.NONE, null);
    }
}
