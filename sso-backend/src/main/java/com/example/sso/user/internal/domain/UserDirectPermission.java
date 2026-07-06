package com.example.sso.user.internal.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An explicit direct (Okta/AWS-style) permission grant to a user (row of {@code app_user_permission}).
 * Replaces the former {@code @ManyToMany} on {@code AppUser}: grant/revoke are visible repository
 * inserts/deletes in the service layer.
 */
@Entity
@Table(name = "app_user_permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class UserDirectPermission {

    @EmbeddedId
    private UserDirectPermissionId id;

    public UserDirectPermission(UUID userId, UUID permissionId) {
        this.id = new UserDirectPermissionId(userId, permissionId);
    }

    public UUID getUserId() {
        return id.userId();
    }

    public UUID getPermissionId() {
        return id.permissionId();
    }
}
