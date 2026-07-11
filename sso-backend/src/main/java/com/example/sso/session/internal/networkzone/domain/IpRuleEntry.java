package com.example.sso.session.internal.networkzone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.UUID;

/**
 * One IP rule of a {@code SessionPolicy} as a cohesive {@code @Embeddable} value object: a reference to a
 * {@link NetworkZone} (by id), whether to ALLOW or BLOCK it, and a {@code priority} ordering evaluation
 * (lower first). Embedded inside {@code SessionPolicyIpRule} (which adds the owning policy). Rules are
 * evaluated first-match — the first rule whose zone CIDRs contain the client IP decides; if none match,
 * access is allowed (the matching itself is resolved against the zone catalog in the security layer).
 */
@Embeddable
public record IpRuleEntry(
        @Column(name = "zone_id", nullable = false) UUID zoneId,
        @Enumerated(EnumType.STRING) @Column(name = "action", nullable = false, length = 8) IpAction action,
        @Column(name = "priority", nullable = false) int priority) {
}
