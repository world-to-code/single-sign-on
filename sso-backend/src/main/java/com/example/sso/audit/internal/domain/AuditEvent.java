package com.example.sso.audit.internal.domain;

import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Immutable-by-design audit record for security-relevant events. Created fully-formed
 * through its constructor; never mutated.
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

    public AuditEvent(AuditType type, String principal, boolean success, String detail, String remoteIp,
                      AuditSubjectType subjectType, String subjectId) {
        this.type = type.name();
        this.principal = principal;
        this.success = success;
        this.detail = detail;
        this.remoteIp = remoteIp;
        this.category = type.getCategory();
        this.subjectType = subjectType == null ? AuditSubjectType.NONE : subjectType;
        this.subjectId = subjectId;
    }
}
