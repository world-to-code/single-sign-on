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
                          AuditSubjectType subjectType, String subjectId, UUID orgId, String reason,
                          boolean verifiedActor) {

    public AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp) {
        this(type, principal, success, detail, remoteIp, AuditSubjectType.NONE, null, null, null, true);
    }

    public AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp,
                       AuditSubjectType subjectType, String subjectId) {
        this(type, principal, success, detail, remoteIp, subjectType, subjectId, null, null, true);
    }

    public AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp,
                       AuditSubjectType subjectType, String subjectId, UUID orgId) {
        this(type, principal, success, detail, remoteIp, subjectType, subjectId, orgId, null, true);
    }

    /** For a tenant-known event with no scopeable subject (the login flow tags its resolved org). */
    public AuditRecord(AuditType type, String principal, boolean success, String detail, String remoteIp,
                       UUID orgId) {
        this(type, principal, success, detail, remoteIp, AuditSubjectType.NONE, null, orgId, null, true);
    }

    /** Returns a copy carrying the structured outcome reason (used at failure/denial sites). */
    public AuditRecord withReason(String reason) {
        return new AuditRecord(type, principal, success, detail, remoteIp, subjectType, subjectId, orgId, reason,
                verifiedActor);
    }

    /**
     * Marks the principal as NOT an authenticated identity — a pre-auth / failed-login input where the caller
     * supplied the username but never proved control of it. The audit service then attributes the event by name
     * only (no account lookup, no id/email enrichment, no enumeration oracle).
     */
    public AuditRecord unverifiedActor() {
        return new AuditRecord(type, principal, success, detail, remoteIp, subjectType, subjectId, orgId, reason,
                false);
    }
}
