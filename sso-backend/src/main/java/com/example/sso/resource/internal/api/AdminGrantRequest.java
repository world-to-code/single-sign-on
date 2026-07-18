package com.example.sso.resource.internal.api;

import com.example.sso.resource.internal.domain.ResourceRoleTier;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Delegates a grant on the resource's subtree to a user: {@code ADMIN} (manage) or {@code VIEWER}
 * (read-only). A request that omits {@code tier} defaults to {@code ADMIN} (back-compatible).
 */
public record AdminGrantRequest(@NotNull UUID userId, ResourceRoleTier tier) {

    /** Convenience: a grant with no explicit tier (resolves to ADMIN). */
    public AdminGrantRequest(UUID userId) {
        this(userId, null);
    }

    public ResourceRoleTier resolvedTier() {
        return tier != null ? tier : ResourceRoleTier.ADMIN;
    }
}
