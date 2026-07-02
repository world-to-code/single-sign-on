package com.example.sso.audit;

import java.time.Instant;

/**
 * Exposed, read-only projection of an audit event. This is the audit module's public
 * contract for reading recorded events; the backing {@code AuditEvent} entity stays
 * module-internal.
 */
public record AuditEntry(Long id, Instant occurredAt, String principal,
                         String type, AuditCategory category, boolean success, String detail,
                         AuditSubjectType subjectType, String subjectId) {
}
