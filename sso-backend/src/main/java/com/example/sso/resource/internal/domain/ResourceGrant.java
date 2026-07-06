package com.example.sso.resource.internal.domain;

import java.util.UUID;

/**
 * A delegation grant value ({@link ResourceRoleTier} over a resource), the value-object view of a
 * {@link ResourceGrantRow}. {@code roleId} (nullable) reserves catalog-role scoping — resource-scoped
 * RBAC — for a later phase.
 */
public record ResourceGrant(UUID userId, ResourceRoleTier tier, UUID roleId) {

    public static ResourceGrant admin(UUID userId) {
        return new ResourceGrant(userId, ResourceRoleTier.ADMIN, null);
    }

    public static ResourceGrant viewer(UUID userId) {
        return new ResourceGrant(userId, ResourceRoleTier.VIEWER, null);
    }
}
