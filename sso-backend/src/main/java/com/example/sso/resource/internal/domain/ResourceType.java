package com.example.sso.resource.internal.domain;

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
 * A resource kind (table {@code resource_type}). Its member-kind constraints — which
 * {@link MemberType}s a resource of this type may contain — are held as explicit
 * {@link ResourceTypeAllowedMember} rows, read/written by the service rather than a mapped collection.
 *
 * <p>{@code orgId} scopes the vocabulary (V82): {@code null} = a GLOBAL/shared type, non-null = a tenant's
 * own type (RLS-isolated). Uniqueness is tier-aware — a name is unique per tenant, or globally.
 */
@Entity
@Table(name = "resource_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class ResourceType extends AuditedEntity implements OrgOwned {

    @Column(nullable = false, length = 100)
    private String name;

    // NULL = a GLOBAL/shared type; non-null = owned by that organization. Set at creation, immutable.
    @Column(name = "org_id")
    private UUID orgId;

    public ResourceType(String name, UUID orgId) {
        this.name = name;
        this.orgId = orgId;
    }
}
