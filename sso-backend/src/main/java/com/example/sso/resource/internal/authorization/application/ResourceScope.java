package com.example.sso.resource.internal.authorization.application;

import java.util.Set;
import java.util.UUID;

/**
 * Shared internal core of the authorization ports: graph reachability over the resource DAG.
 * The per-domain ports ({@code UserAuthorization}, {@code GroupAuthorization}, …) delegate their
 * scope questions here; permission (PBAC) stays in {@code @RequirePermission}/{@code @Can*}.
 */
public interface ResourceScope {

    /** Resources the actor administers: ADMIN-granted resources plus all DAG descendants. */
    Set<UUID> managedResourceIds(UUID actorUserId);

    /**
     * Resources the actor may VIEW: ANY-tier-granted resources (ADMIN or VIEWER) plus all DAG descendants —
     * a superset of {@link #managedResourceIds}. The extra members are a pure VIEWER's read-only subtree.
     */
    Set<UUID> viewableResourceIds(UUID actorUserId);

    /** Super-admin bypass: an unscoped actor (direct {@code ROLE_ADMIN}) sees everything. */
    boolean isUnscoped(UUID actorUserId);

    /** Whether {@code descendantId} lies in {@code ancestorId}'s subtree (inclusive). */
    boolean reaches(UUID ancestorId, UUID descendantId);
}
