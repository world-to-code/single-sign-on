package com.example.sso.user.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite identity of a {@link RoleHierarchyEdge} row ({@code role_hierarchy}): the parent→child pair. */
@Embeddable
public record RoleHierarchyEdgeId(
        @Column(name = "parent_role_id", nullable = false) UUID parentRoleId,
        @Column(name = "child_role_id", nullable = false) UUID childRoleId) implements Serializable {
}
