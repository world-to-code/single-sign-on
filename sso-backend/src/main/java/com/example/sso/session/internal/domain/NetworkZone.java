package com.example.sso.session.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A named, reusable IP network zone: a label (e.g. "Corporate network"). Session policies reference zones
 * (allow/block) instead of inlining CIDRs, so a range is defined once and reused across policies. The zone's
 * CIDR ranges live in their own {@link NetworkZoneCidr} rows, written explicitly by the service — this entity
 * carries only the zone's own columns. No setters — mutate via {@link #update}.
 */
@Entity
@Table(name = "network_zone")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class NetworkZone extends AuditedEntity implements OrgOwned {

    // Tier-aware uniqueness (partial indexes in V47): global name, or (org_id, name) per tenant.
    @Column(nullable = false, length = 100)
    private String name;

    // NULL = a GLOBAL zone (platform-wide); non-null = owned by that organization (RLS-isolated). Fixed
    // at creation — a tenant may only reference zones visible in its own context in a session policy rule.
    @Column(name = "org_id")
    private UUID orgId;

    @Column(length = 255)
    private String description;

    /** A global zone (no owning org). */
    public NetworkZone(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /** A zone owned by {@code orgId} (null = global). The org is fixed at creation. */
    public NetworkZone(String name, String description, UUID orgId) {
        this(name, description);
        this.orgId = orgId;
    }

    public void update(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
