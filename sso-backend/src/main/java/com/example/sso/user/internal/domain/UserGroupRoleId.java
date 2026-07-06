package com.example.sso.user.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite identity of a {@link UserGroupRole} row ({@code group_role}). */
@Embeddable
public record UserGroupRoleId(
        @Column(name = "group_id", nullable = false) UUID groupId,
        @Column(name = "role_id", nullable = false) UUID roleId) implements Serializable {
}
