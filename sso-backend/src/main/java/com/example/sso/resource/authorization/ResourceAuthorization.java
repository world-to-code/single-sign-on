package com.example.sso.resource.authorization;

import java.util.Set;
import java.util.UUID;

/**
 * Scope decisions about resources themselves: whether the actor's subtree covers a resource, and the
 * full set of resources they administer. Answers ONLY the ABAC scope question — the permission check
 * stays in {@code @RequirePermission}/{@code @Can*} (PBAC), which composes with these ports.
 *
 * <p>{@code canView} (any-tier reach) is a superset of {@code canManage} (ADMIN reach): a VIEWER grant
 * confers read-only scope over its subtree. The other domain ports (user/group/application) still equate the
 * two pending admin-surface view-gating (a documented follow-up).
 */
public interface ResourceAuthorization {

    /**
     * Whether the actor is unscoped (a direct super {@code ROLE_ADMIN}). The {@code scopedXIds}/
     * {@code managedResourceIds}/{@code viewableResourceIds} methods return only EXPLICITLY scoped ids —
     * list-filtering callers must branch on this first (an unscoped actor sees everything, unfiltered).
     */
    boolean isUnscoped(UUID actorUserId);

    /** Read reach: the resource is in the actor's ADMIN or VIEWER subtree. */
    boolean canView(UUID actorUserId, UUID resourceId);

    /** Manage reach: the resource is in the actor's ADMIN subtree only. */
    boolean canManage(UUID actorUserId, UUID resourceId);

    /** Resources the actor administers (ADMIN grants + all DAG descendants); empty when scoped out. */
    Set<UUID> managedResourceIds(UUID actorUserId);

    /** Resources the actor may view (ADMIN or VIEWER grants + all DAG descendants); empty when scoped out. */
    Set<UUID> viewableResourceIds(UUID actorUserId);
}
