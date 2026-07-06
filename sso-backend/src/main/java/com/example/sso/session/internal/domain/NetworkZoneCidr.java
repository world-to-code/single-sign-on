package com.example.sso.session.internal.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * One CIDR range of a {@link NetworkZone}, mapped explicitly onto the existing {@code network_zone_cidr}
 * table so inserts/deletes are issued by the service rather than a hidden JPA collection cascade. The whole
 * row (zone + CIDR) is the identity — mirroring the {@code Set<String>} semantics it replaces.
 */
@Entity
@Table(name = "network_zone_cidr")
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class NetworkZoneCidr {

    @EmbeddedId
    private NetworkZoneCidrId id;

    public NetworkZoneCidr(UUID zoneId, String cidr) {
        this.id = new NetworkZoneCidrId(zoneId, cidr);
    }

    public UUID zoneId() {
        return id.zoneId();
    }

    public String cidr() {
        return id.cidr();
    }
}
