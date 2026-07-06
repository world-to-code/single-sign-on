package com.example.sso.resource.internal.domain;

import java.io.Serializable;
import java.util.UUID;

/** Composite identifier for {@link ResourceGrantRow} — (resource, user, tier); {@code roleId} is not a key. */
public record ResourceGrantRowId(UUID resourceId, UUID userId, ResourceRoleTier tier)
        implements Serializable {
}
