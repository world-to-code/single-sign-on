package com.example.sso.session.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * One IP access rule of a {@link SessionPolicy}: a CIDR range, whether to ALLOW or BLOCK it, and a
 * {@code priority} that orders evaluation (lower first). Rules are evaluated first-match — the first
 * rule whose CIDR contains the client IP decides; if none match, access is allowed.
 */
@Embeddable
public record IpRuleEntry(
        @Column(name = "cidr", nullable = false, length = 64) String cidr,
        @Enumerated(EnumType.STRING) @Column(name = "action", nullable = false, length = 8) IpAction action,
        @Column(name = "priority", nullable = false) int priority) {
}
