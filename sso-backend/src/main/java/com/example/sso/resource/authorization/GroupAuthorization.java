package com.example.sso.resource.authorization;

import java.util.Set;
import java.util.UUID;

/**
 * Scope decisions about groups: a group is in scope when it is a member of one of the actor's
 * managed resources (or the actor is unscoped). ABAC scope only — PBAC stays in
 * {@code @RequirePermission}/{@code @Can*}. {@code canView} equals {@code canManage} until Phase 2.
 */
public interface GroupAuthorization {

    boolean canView(UUID actorUserId, UUID groupId);

    boolean canManage(UUID actorUserId, UUID groupId);

    /** Groups inside the actor's managed resources. Empty for a scoped actor with no grants. */
    Set<UUID> scopedGroupIds(UUID actorUserId);
}
