package com.example.sso.user.internal.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An explicit role↔permission grant (row of {@code role_permission}). Replaces the former
 * {@code @ManyToMany} on {@code Role}: the permissions a role carries are now managed as visible
 * repository inserts/deletes in the service layer.
 */
@Entity
@Table(name = "role_permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class RolePermission {

    @EmbeddedId
    private RolePermissionId id;

    public RolePermission(UUID roleId, UUID permissionId) {
        this.id = new RolePermissionId(roleId, permissionId);
    }

    public UUID getRoleId() {
        return id.roleId();
    }

    public UUID getPermissionId() {
        return id.permissionId();
    }
}
