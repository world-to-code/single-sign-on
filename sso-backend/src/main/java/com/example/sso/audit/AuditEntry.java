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
}
