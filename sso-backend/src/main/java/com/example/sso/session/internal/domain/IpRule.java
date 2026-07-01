package com.example.sso.session.internal.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** An IP access rule: ALLOW or BLOCK a CIDR range. Lower priority is evaluated first. */
@Entity
@Table(name = "ip_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IpRule {

    public enum Action { ALLOW, BLOCK }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String cidr;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Action action;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int priority = 100;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public IpRule(String cidr, Action action, String description, boolean enabled, int priority) {
        this.cidr = cidr;
        this.action = action;
        this.description = description;
        this.enabled = enabled;
        this.priority = priority;
    }

    public void update(String cidr, Action action, String description, boolean enabled, int priority) {
        this.cidr = cidr;
        this.action = action;
        this.description = description;
        this.enabled = enabled;
        this.priority = priority;
    }
}
