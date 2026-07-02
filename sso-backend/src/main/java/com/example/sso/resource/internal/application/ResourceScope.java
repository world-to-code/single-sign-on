package com.example.sso.resource.internal.application;

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

    /** Super-admin bypass: an unscoped actor (direct {@code ROLE_ADMIN}) sees everything. */
    boolean isUnscoped(UUID actorUserId);

    /** Whether {@code descendantId} lies in {@code ancestorId}'s subtree (inclusive). */
    boolean reaches(UUID ancestorId, UUID descendantId);
}
