package com.example.sso.resource;

import java.util.Set;
import java.util.UUID;

/**
 * Scope decisions about users: a user is in scope when they are a direct USER member of a managed
 * resource, or belong to a group that is (or the actor is unscoped). ABAC scope only — PBAC stays in
 * {@code @RequirePermission}/{@code @Can*}. {@code canView} equals {@code canManage} until Phase 2.
 */
public interface UserAuthorization {

    boolean canView(UUID actorUserId, UUID targetUserId);

    boolean canManage(UUID actorUserId, UUID targetUserId);

    /** Users within the actor's managed resources (direct members + members of scoped groups). */
    Set<UUID> scopedUserIds(UUID actorUserId);
}
