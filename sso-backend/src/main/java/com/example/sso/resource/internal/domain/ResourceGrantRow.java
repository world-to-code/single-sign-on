package com.example.sso.resource.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One delegation grant (row of {@code resource_role}): the user holds {@code tier} over
 * {@code resourceId}'s subtree. An explicit entity replaces the former {@code @ElementCollection} so
 * the service inserts/deletes grant rows directly — and the replace-on-regrant semantic (delete the
 * old row, insert the new) is spelled out in the service.
 *
 * <p>Unlike {@link ResourceMemberRow} (whose whole row is its key, so its {@link ResourceMember} value
 * object embeds cleanly in the id), a grant cannot embed {@link ResourceGrant} as one unit: the
 * primary key is (resource, user, tier) while {@code roleId} is a NULLABLE, NON-key column. Its fields
 * straddle the identity boundary — {@code tier} is a key column, {@code roleId} is not and may be null,
 * so it can neither join the key nor be split out of a single embeddable. The value object is therefore
 * kept behaviour-carrying via {@link ResourceGrant#admin}/{@link ResourceGrant#viewer} and the
 * {@link #of} conversion rather than an {@code @Embedded} mapping.
 */
@Entity
@Table(name = "resource_role")
@IdClass(ResourceGrantRowId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class ResourceGrantRow {

    @Id
    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 20)
    private ResourceRoleTier tier;

    @Column(name = "role_id")
    private UUID roleId;

    /** Owning tenant (= the granted resource's org), or {@code null} for a global resource. */
    @Column(name = "org_id")
    private UUID orgId;

    public ResourceGrantRow(UUID resourceId, UUID userId, ResourceRoleTier tier, UUID roleId, UUID orgId) {
        this.resourceId = resourceId;
        this.userId = userId;
        this.tier = tier;
        this.roleId = roleId;
        this.orgId = orgId;
    }

    public static ResourceGrantRow of(UUID resourceId, ResourceGrant grant, UUID orgId) {
        return new ResourceGrantRow(resourceId, grant.userId(), grant.tier(), grant.roleId(), orgId);
    }
}
