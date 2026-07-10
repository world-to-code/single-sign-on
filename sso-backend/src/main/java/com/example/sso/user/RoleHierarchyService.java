package com.example.sso.user;

import java.util.Set;
import java.util.UUID;

/**
 * The DAG-position (dominance) authority for the role-inheritance hierarchy: which roles a given actor
 * strictly OUTRANKS (their held roles' transitive descendants) and which strictly outrank the actor
 * (their held roles' transitive ancestors). Consumed by the admin authorization policy to bound what a
 * non-super admin may assign or even see: an admin may act only on roles strictly below their own
 * position — never a peer, never one above.
 *
 * <p>Position is computed from the actor's HELD role ids (direct + group-delegated), not from their
 * authority strings — a custom role contributes no name authority, so only the id-based walk is truthful.
 * All walks are RLS-confined to the acting tier.
 */
public interface RoleHierarchyService {

    /** Whether {@code targetRoleId} sits strictly BELOW the actor in the DAG (a proper descendant). */
    boolean actorDominatesRole(UUID actorUserId, UUID targetRoleId);

    /**
     * Whether the role named {@code roleName}, resolved in {@code actingOrg}'s tier (org-first, then global —
     * the same resolution the assignment uses), sits strictly below the actor. Fails CLOSED: an unknown or
     * unresolved name is never dominated.
     */
    boolean actorDominatesRoleName(UUID actorUserId, String roleName, UUID actingOrg);

    /** The roles that strictly outrank the actor: the transitive ancestors of their held roles (self excluded). */
    Set<UUID> rolesAboveActor(UUID actorUserId);

    /**
     * The actor's APEX roles: the held roles NOT dominated by another of their held roles — the top of their
     * position. A new custom role must be attached below these (never below an intermediate held role, which
     * would grow that role's effective permissions for its OTHER holders).
     */
    Set<UUID> apexRolesOf(UUID actorUserId);
}
