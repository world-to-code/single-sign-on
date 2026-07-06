package com.example.sso.session.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite key of {@link NetworkZoneCidr}: the owning zone and one CIDR string (the whole row is the key). */
@Embeddable
record NetworkZoneCidrId(
        @Column(name = "zone_id", nullable = false) UUID zoneId,
        @Column(name = "cidr", nullable = false, length = 64) String cidr) implements Serializable {
}
