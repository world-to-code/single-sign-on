package com.example.sso.user.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite identity of a {@link UserRole} row ({@code app_user_role}). */
@Embeddable
public record UserRoleId(
        @Column(name = "user_id", nullable = false) UUID userId,
        @Column(name = "role_id", nullable = false) UUID roleId) implements Serializable {
}
