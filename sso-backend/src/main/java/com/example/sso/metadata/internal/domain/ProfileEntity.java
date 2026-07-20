package com.example.sso.metadata.internal.domain;

import com.example.sso.metadata.ProfileKind;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;

/** A tenant's named profile. See {@code V125} for why there is no global tier here. */
@Entity
@Table(name = "profile")
@Getter
public class ProfileEntity extends AuditedEntity implements OrgOwned {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private ProfileKind kind;

    @Column(name = "connector_id")
    private UUID connectorId;

    @Column(name = "system", nullable = false)
    private boolean system;

    @Column(name = "default_for_creation", nullable = false)
    private boolean defaultForCreation;

    protected ProfileEntity() {
    }

    private ProfileEntity(UUID orgId, String name, ProfileKind kind, UUID connectorId, boolean system,
            boolean defaultForCreation) {
        this.orgId = orgId;
        this.name = name;
        this.kind = kind;
        this.connectorId = connectorId;
        this.system = system;
        this.defaultForCreation = defaultForCreation;
    }

    /**
     * The tenant's own profile, named after the organization. It is the one users are created from until an
     * administrator designates another, and it cannot be deleted — a tenant with no profile could not describe
     * a user at all.
     */
    public static ProfileEntity tenantDefault(UUID orgId, String name) {
        return new ProfileEntity(orgId, name, ProfileKind.TENANT, null, true, true);
    }

    /** A profile describing one identity source; it lives and dies with the connector it reads. */
    public static ProfileEntity forConnector(UUID orgId, String name, ProfileKind kind, UUID connectorId) {
        return new ProfileEntity(orgId, name, kind, connectorId, false, false);
    }

    public void rename(String name) {
        this.name = name;
    }
}
