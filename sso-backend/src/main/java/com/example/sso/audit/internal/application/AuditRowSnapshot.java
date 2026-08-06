package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditActorType;
import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditSeverity;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.internal.domain.AuditEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Everything about one audit row that the hash chain commits to.
 *
 * <p>It exists as a projection rather than the entity because the digest has to be computable and testable
 * without a database, and because listing the committed fields EXPLICITLY is the point: a field that is not
 * here can be edited without the chain noticing. {@code AuditChainCoverageTest} fails when the entity gains a
 * column that is not mirrored here, so leaving one out becomes a decision somebody made rather than one nobody
 * saw.
 *
 * @param id the row's primary key, committed so a row cannot be moved to a different position in the chain
 */
public record AuditRowSnapshot(
        Long id,
        Instant occurredAt,
        String principal,
        String type,
        String detail,
        String remoteIp,
        boolean success,
        AuditCategory category,
        AuditSubjectType subjectType,
        String subjectId,
        UUID orgId,
        AuditActorType actorType,
        UUID actorId,
        String actorEmail,
        String actorDisplay,
        String userAgent,
        String device,
        String requestId,
        String reason,
        AuditSeverity severity) {

    /** Projects the stored row onto exactly the fields the chain commits to. */
    public static AuditRowSnapshot of(AuditEvent event) {
        return new AuditRowSnapshot(event.getId(), event.getOccurredAt(), event.getPrincipal(), event.getType(),
                event.getDetail(), event.getRemoteIp(), event.isSuccess(), event.getCategory(),
                event.getSubjectType(), event.getSubjectId(), event.getOrgId(), event.getActorType(),
                event.getActorId(), event.getActorEmail(), event.getActorDisplay(), event.getUserAgent(),
                event.getDevice(), event.getRequestId(), event.getReason(), event.getSeverity());
    }
}
