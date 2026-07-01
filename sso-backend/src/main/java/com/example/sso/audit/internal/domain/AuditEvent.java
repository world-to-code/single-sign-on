package com.example.sso.audit.internal.domain;

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

    public AuditEvent(String type, String principal, boolean success, String detail, String remoteIp) {
        this.type = type;
        this.principal = principal;
        this.success = success;
        this.detail = detail;
        this.remoteIp = remoteIp;
    }
}
