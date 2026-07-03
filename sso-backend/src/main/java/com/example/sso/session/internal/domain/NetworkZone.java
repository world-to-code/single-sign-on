package com.example.sso.session.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A named, reusable IP network zone: a label (e.g. "Corporate network") and the CIDR ranges it covers.
 * Session policies reference zones (allow/block) instead of inlining CIDRs, so a range is defined once and
 * reused across policies. No setters — mutate via {@link #update}.
 */
@Entity
@Table(name = "network_zone")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class NetworkZone extends AuditedEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 255)
    private String description;

    @ElementCollection
    @CollectionTable(name = "network_zone_cidr", joinColumns = @JoinColumn(name = "zone_id"))
    @Column(name = "cidr", nullable = false, length = 64)
    private Set<String> cidrs = new HashSet<>();

    public NetworkZone(String name, String description, Collection<String> cidrs) {
        this.name = name;
        this.description = description;
        this.cidrs.addAll(cidrs);
    }

    public void update(String name, String description, Collection<String> cidrs) {
        this.name = name;
        this.description = description;
        this.cidrs.clear();
        this.cidrs.addAll(cidrs);
    }

    public List<String> cidrList() {
        return List.copyOf(cidrs);
    }
}
