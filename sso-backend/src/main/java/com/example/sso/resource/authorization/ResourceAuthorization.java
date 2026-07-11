package com.example.sso.resource.authorization;

import java.util.Set;
import java.util.UUID;

/**
 * Scope decisions about resources themselves: whether the actor's subtree covers a resource, and the
 * full set of resources they administer. Answers ONLY the ABAC scope question — the permission check
 * stays in {@code @RequirePermission}/{@code @Can*} (PBAC), which composes with these ports.
 *
 * <p>Until VIEWER-tier semantics land (Phase 2), {@code canView} equals {@code canManage}.
 */
public interface ResourceAuthorization {

    /**
     * Whether the actor is unscoped (a direct super {@code ROLE_ADMIN}). The {@code scopedXIds}/
     * {@code managedResourceIds} methods return only EXPLICITLY scoped ids — list-filtering callers
     * must branch on this first (an unscoped actor sees everything, unfiltered).
     */
    boolean isUnscoped(UUID actorUserId);

    boolean canView(UUID actorUserId, UUID resourceId);

    boolean canManage(UUID actorUserId, UUID resourceId);

    /** Resources the actor administers (ADMIN grants + all DAG descendants); empty when scoped out. */
    Set<UUID> managedResourceIds(UUID actorUserId);
}
