package com.example.sso.user.internal.role.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A directed edge in the role-inheritance DAG ({@code role_hierarchy}): the parent role INHERITS the
 * child role's permission set (transitively). Drives both the permission-union computed at login and the
 * dominance predicate that bounds which roles a non-super admin may see or assign.
 *
 * <p>{@code orgId} scopes the edge exactly like {@code role} (V43) and the resource graph (V56): NULL is a
 * global edge, non-null a tenant-owned one. The cross-tier seed edge (global {@code ROLE_ADMIN} → a
 * tenant's {@code ROLE_ORG_ADMIN}) is stamped with the CHILD's org, so it is confined to that tenant.
 */
@Entity
@Table(name = "role_hierarchy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class RoleHierarchyEdge {

    @EmbeddedId
    private RoleHierarchyEdgeId id;

    @Column(name = "org_id")
    private UUID orgId;

    public RoleHierarchyEdge(UUID parentRoleId, UUID childRoleId, UUID orgId) {
        this.id = new RoleHierarchyEdgeId(parentRoleId, childRoleId);
        this.orgId = orgId;
    }

    public UUID getParentRoleId() {
        return id.parentRoleId();
    }

    public UUID getChildRoleId() {
        return id.childRoleId();
    }
}
