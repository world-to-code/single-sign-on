package com.example.sso.session.internal.domain;
import com.example.sso.shared.domain.AuditedEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


/** An IP access rule: ALLOW or BLOCK a CIDR range. Lower priority is evaluated first. */
@Entity
@Table(name = "ip_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IpRule extends AuditedEntity {

    public enum Action { ALLOW, BLOCK }

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
