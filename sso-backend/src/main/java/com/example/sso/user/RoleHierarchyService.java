package com.example.sso.user;

import java.util.Set;
import java.util.UUID;

/**
 * The DAG-position authority for the role-inheritance hierarchy: which roles a given actor may manage
 * (everything NOT strictly above their own position) and which strictly outrank them. Consumed by the
 * admin authorization policy to bound what a non-super admin may assign or even see: an admin may act on
 * roles at OR below their own level — never one above. (Peer/at-level management is safe: the
 * grant-only-what-you-hold and platform-permission guards, applied alongside, stop any actual escalation.)
 *
 * <p>Position is computed from the actor's HELD role ids (direct + group-delegated), not from their
 * authority strings — a custom role contributes no name authority, so only the id-based walk is truthful.
 * All walks are RLS-confined to the acting tier.
 */
public interface RoleHierarchyService {

    /** Whether {@code targetRoleId} is NOT strictly above the actor — i.e. at or below their level (manageable). */
    boolean actorMayManageRole(UUID actorUserId, UUID targetRoleId);

    /**
     * Whether the role named {@code roleName}, resolved in {@code actingOrg}'s tier (org-first, then global —
     * the same resolution the assignment uses), is at or below the actor's level (not strictly above). Fails
     * CLOSED: an unknown or unresolved name is never manageable.
     */
    boolean actorMayManageRoleName(UUID actorUserId, String roleName, UUID actingOrg);

    /**
     * The roles that strictly outrank the actor: the ancestors of the actor's APEX (highest-held) roles. The
     * actor's position is their highest role, so an actor holding a low role (e.g. ROLE_USER) alongside a
     * high one (ROLE_ORG_ADMIN) is judged by the high one — the low role's ancestry (which includes the
     * actor's OWN higher roles) is never miscounted as being above them.
     */
    Set<UUID> rolesAboveActor(UUID actorUserId);

    /**
     * The actor's APEX roles: the held roles NOT dominated by another of their held roles — the top of their
     * position. A new custom role must be attached below these (never below an intermediate held role, which
     * would grow that role's effective permissions for its OTHER holders).
     */
    Set<UUID> apexRolesOf(UUID actorUserId);
}
