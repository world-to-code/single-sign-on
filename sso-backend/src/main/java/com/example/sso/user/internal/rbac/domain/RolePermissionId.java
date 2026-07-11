package com.example.sso.user.internal.rbac.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite identity of a {@link RolePermission} row ({@code role_permission}). */
@Embeddable
public record RolePermissionId(
        @Column(name = "role_id", nullable = false) UUID roleId,
        @Column(name = "permission_id", nullable = false) UUID permissionId) implements Serializable {
}
