package com.example.sso.resource.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.UUID;

/**
 * A delegation grant on a resource (row of {@code resource_role}): the user holds the given
 * {@link ResourceRoleTier} over the resource's subtree. {@code roleId} (nullable) reserves
 * catalog-role scoping — resource-scoped RBAC — for a later phase.
 */
@Embeddable
public record ResourceGrant(
        @Column(name = "user_id", nullable = false) UUID userId,
        @Enumerated(EnumType.STRING) @Column(name = "tier", nullable = false, length = 20) ResourceRoleTier tier,
        @Column(name = "role_id") UUID roleId) {

    public static ResourceGrant admin(UUID userId) {
        return new ResourceGrant(userId, ResourceRoleTier.ADMIN, null);
    }

    public static ResourceGrant viewer(UUID userId) {
        return new ResourceGrant(userId, ResourceRoleTier.VIEWER, null);
    }
}
