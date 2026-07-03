package com.example.sso.session.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.UUID;

/**
 * One IP rule of a {@link SessionPolicy}: a reference to a {@link NetworkZone} (by id), whether to ALLOW or
 * BLOCK it, and a {@code priority} ordering evaluation (lower first). Rules are evaluated first-match — the
 * first rule any of whose zone CIDRs contains the client IP decides; if none match, access is allowed.
 */
@Embeddable
public record IpRuleEntry(
        @Column(name = "zone_id", nullable = false) UUID zoneId,
        @Enumerated(EnumType.STRING) @Column(name = "action", nullable = false, length = 8) IpAction action,
        @Column(name = "priority", nullable = false) int priority) {
}
