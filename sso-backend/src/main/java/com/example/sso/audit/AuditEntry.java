package com.example.sso.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Exposed, read-only projection of an audit event — the audit module's public contract for reading
 * recorded events (the backing {@code AuditEvent} entity stays module-internal). Beyond the core
 * who/what/outcome, it surfaces the enrichment used for SIEM triage: the structured actor identity
 * ({@code actorType}/{@code actorId}/{@code actorEmail}/{@code actorDisplay}), the client context
 * ({@code remoteIp}/{@code userAgent}/{@code device}), the correlation id ({@code requestId}), a
 * structured outcome {@code reason}, and the {@code severity}.
 */
public record AuditEntry(Long id, Instant occurredAt, String principal,
                         String type, AuditCategory category, boolean success, String detail,
                         AuditSubjectType subjectType, String subjectId,
                         AuditActorType actorType, UUID actorId, String actorEmail, String actorDisplay,
                         String remoteIp, String userAgent, String device, String requestId,
                         String reason, AuditSeverity severity) {

    /**
     * A copy with the actor's and client's personal/tracking identifiers removed, for a reader without the
     * {@code audit:read:pii} grant: the actor email + display name, the account id (a stable correlation
     * identifier), and the client context (IP, User-Agent, device, request/correlation id) are all nulled. Only
     * the coarse actor TYPE, the principal name (the event's inherent subject), and the outcome/context remain —
     * so a redacted row reveals neither who exactly nor from where, and cannot be correlated across events by id.
     */
    public AuditEntry withoutPii() {
        return new AuditEntry(id, occurredAt, principal, type, category, success, detail, subjectType, subjectId,
                actorType, null, null, null, null, null, null, null, reason, severity);
    }
}
