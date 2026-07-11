package com.example.sso.user.internal.rbac.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite identity of a {@link UserDirectPermission} row ({@code app_user_permission}). */
@Embeddable
public record UserDirectPermissionId(
        @Column(name = "user_id", nullable = false) UUID userId,
        @Column(name = "permission_id", nullable = false) UUID permissionId) implements Serializable {
}
