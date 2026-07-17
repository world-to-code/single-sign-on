package com.example.sso.audit.internal.domain;

import com.example.sso.audit.AuditActorType;
import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditSeverity;
import com.example.sso.audit.AuditSubjectType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable-by-design audit record for security-relevant events. Assembled fully-formed through the
 * {@link #of} factory (never mutated), enriching the raw {@link AuditRecord} with the resolved actor
 * identity, client context, and triage severity for SIEM consumption.
 */
@Entity
@Table(name = "audit_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(length = 200)
    private String principal;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "remote_ip", length = 64)
    private String remoteIp;

    @Column(nullable = false)
    private boolean success = true;

    /** Coarse classification for the admin log view; derived from {@code type}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuditCategory category;

    /** The scopeable object this event acts upon, so the admin log can be filtered to a subtree. */
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private AuditSubjectType subjectType = AuditSubjectType.NONE;

    @Column(name = "subject_id", length = 255)
    private String subjectId;

    /** The tenant this event occurred in (analytics dimension), or null when there is no resolvable org. */
    @Column(name = "org_id")
    private UUID orgId;

    // --- Enrichment: who (structured actor) ---
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", length = 16)
    private AuditActorType actorType;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(name = "actor_display", length = 255)
    private String actorDisplay;

    // --- Enrichment: from where (client context) ---
    @Column(name = "user_agent", columnDefinition = "text")
    private String userAgent;

    @Column(length = 128)
    private String device;

    /** Correlation id (W3C trace id) tying this event to the rest of the request's telemetry. */
    @Column(name = "request_id", length = 64)
    private String requestId;

    // --- Enrichment: outcome detail + triage ---
    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditSeverity severity;

    @Builder(access = AccessLevel.PRIVATE)
    private AuditEvent(String type, String principal, boolean success, String detail, String remoteIp,
                       AuditCategory category, AuditSubjectType subjectType, String subjectId, UUID orgId,
                       AuditActorType actorType, UUID actorId, String actorEmail, String actorDisplay,
                       String userAgent, String device, String requestId, String reason, AuditSeverity severity) {
        this.type = type;
        this.principal = principal;
        this.success = success;
        this.detail = detail;
        this.remoteIp = remoteIp;
        this.category = category;
        this.subjectType = subjectType == null ? AuditSubjectType.NONE : subjectType;
        this.subjectId = subjectId;
        this.orgId = orgId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.actorDisplay = actorDisplay;
        this.userAgent = userAgent;
        this.device = device;
        this.requestId = requestId;
        this.reason = reason;
        this.severity = severity;
    }

    /**
     * Builds a persisted event from the raw record plus the resolved enrichment. The explicit
     * {@code remoteIp} on the record wins (the caller knew it); the captured client IP is the fallback.
     */
    public static AuditEvent of(AuditRecord record, AuditActorInfo actor, AuditClientInfo client,
                                AuditSeverity severity, UUID orgId) {
        return builder()
                .type(record.type().name())
                .category(record.type().getCategory())
                .principal(record.principal())
                .success(record.success())
                .detail(record.detail())
                .remoteIp(record.remoteIp() != null ? record.remoteIp() : client.ip())
                .subjectType(record.subjectType())
                .subjectId(record.subjectId())
                .orgId(orgId)
                .actorType(actor.type())
                .actorId(actor.id())
                .actorEmail(actor.email())
                .actorDisplay(actor.displayName())
                .userAgent(client.userAgent())
                .device(client.device())
                .requestId(client.requestId())
                .reason(record.reason())
                .severity(severity)
                .build();
    }
}
