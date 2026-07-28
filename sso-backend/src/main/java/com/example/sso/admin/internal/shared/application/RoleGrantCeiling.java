package com.example.sso.admin.internal.shared.application;

import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleHierarchyService;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * How far UP an administrator may hand authority — the escalation floor, asked once reach is already settled.
 *
 * <p>Three terms compose with AND for a non-super, and each closed a real hole:
 *
 * <ul>
 *   <li><b>Dominance.</b> The role is not strictly above the actor in the inheritance DAG.</li>
 *   <li><b>No platform grant.</b> It carries no platform-only permission, or a tenant admin could hand out a
 *       super-created role that bundles one.</li>
 *   <li><b>Grant only what you hold.</b> The actor already holds every permission it carries. This is the real
 *       floor: without it a tenant admin assigns a role bearing an authority they lack and escalates.</li>
 * </ul>
 *
 * <p>Everything here resolves BY ID wherever it can, and that is not a style choice. A role NAME resolves
 * org-first with a global fallback while the write binds a stored id — so a tenant admin who minted a benign
 * local role of the same name cleared the ceiling on that one and received the privileged global role. Two
 * partial unique indexes let both rows exist, and only four names are reserved, so the collision was theirs to
 * create. The by-name entries that remain (an endpoint that genuinely takes names) resolve in the ACTING TIER,
 * exactly as the assignment will, and fail CLOSED on an unknown name.
 *
 * <p>The bulk entries resolve the actor ONCE for the whole set: asked per group, a file naming two hundred
 * groups re-derived the actor from the database every time.
 */
@Component
@RequiredArgsConstructor
class RoleGrantCeiling {

    private final ActingAdmin actingAdmin;
    private final AdminScope scope;
    private final RoleService roleService;
    private final RoleHierarchyService roleHierarchy;
    private final UserGroupService userGroups;

    /** Whether the CURRENT actor may make a mapping rule assign this target — the manual path's authority. */
    boolean mayAssignTarget(MappingTargetKind kind, UUID targetId) {
        return actingAdmin.id()
                .map(actorId -> mayAssignTarget(actorId, actingAdmin.authorities(), kind, targetId))
                .orElse(false);
    }

    /**
     * The same decision for an EXPLICIT actor and authority set, so it can be evaluated off the request thread
     * (the async re-validation of a mapping rule's author). One implementation, so the manual gate and the
     * continuous re-check can never drift.
     */
    boolean mayAssignTarget(UUID actorId, Set<String> actorAuthorities, MappingTargetKind kind, UUID targetId) {
        return switch (kind) {
            case GROUP -> scope.canAccessGroup(actorId, targetId)
                    && mayConferRolesOf(actorId, actorAuthorities, Set.of(targetId)).contains(targetId);
            case ROLE -> actingAdmin.isSuper(actorId) || mayGrantRole(actorId, actorAuthorities, targetId);
            case RESOURCE_MEMBER -> scope.canManageResource(actorId, targetId);
        };
    }

    /**
     * Which of these groups the actor may put a member into: reach AND the roles the membership would confer.
     * Reach alone is "administers the group's org", which is strictly weaker than a direct role grant's
     * ceiling — a group delegates its roles to every member, so putting someone in one is a role grant wearing
     * a different name.
     *
     * <p>Both the delegation read and each distinct role's verdict happen ONCE for the whole set.
     */
    Set<UUID> mayConferRolesOf(UUID actorId, Set<String> actorAuthorities, Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return Set.of();
        }
        Map<UUID, Set<UUID>> delegated = userGroups.delegatedRoleIds(groupIds);
        Map<UUID, Boolean> verdicts = new HashMap<>();
        Set<UUID> conferrable = new LinkedHashSet<>();
        for (UUID groupId : groupIds) {
            // Absent means the group delegates nothing, so there is no ceiling for it to clear.
            Set<UUID> roles = delegated.getOrDefault(groupId, Set.of());
            if (roles.stream().allMatch(roleId -> verdicts.computeIfAbsent(roleId,
                    id -> mayAssignTarget(actorId, actorAuthorities, MappingTargetKind.ROLE, id)))) {
                conferrable.add(groupId);
            }
        }
        return conferrable;
    }

    /** Whether the current actor may delegate ALL of these roles. All or nothing: a partial delegation is not
     *  something the endpoint can express, so one role they may not hand out fails the request. */
    boolean mayAssignRoleIds(Collection<UUID> roleIds) {
        // Null-safe because this runs inside SpEL, before any binding validation: an omitted roleIds would
        // otherwise NPE out of the gate as a 500 rather than a refusal the caller can read.
        if (roleIds == null || roleIds.isEmpty()) {
            return true;
        }
        Optional<UUID> actor = actingAdmin.id();
        if (actor.isEmpty()) {
            return false;
        }
        Set<String> authorities = actingAdmin.authorities();
        return roleIds.stream().allMatch(roleId ->
                mayAssignTarget(actor.get(), authorities, MappingTargetKind.ROLE, roleId));
    }

    Set<UUID> currentMayConferRolesOf(Collection<UUID> groupIds) {
        return actingAdmin.id()
                .map(actorId -> mayConferRolesOf(actorId, actingAdmin.authorities(), groupIds))
                .orElseGet(Set::of);
    }

    /** The by-NAME entry, for the endpoints that take names. Each term fails closed on an unknown name. */
    boolean mayAssignRoles(Collection<String> roleNames) {
        if (currentIsSuperAdmin() || roleNames == null) {
            return true;
        }
        return roleNames.stream().allMatch(name ->
                currentActorMayManageRoleName(name)
                        && !roleCarriesPlatformPermission(name)
                        && actorHoldsAllPermissionsOfRole(name));
    }

    /** Holds {@code ROLE_ADMIN} DIRECTLY — gates super-only grants; visibility uses the scope's unscoped test. */
    boolean currentIsSuperAdmin() {
        return actingAdmin.id().map(actingAdmin::isSuper).orElse(false);
    }

    /** The actor's APEX roles — where a role they create must attach so it sits strictly beneath them. */
    Set<UUID> currentActorApexRoleIds() {
        return actingAdmin.id().map(roleHierarchy::apexRolesOf).orElse(Set.of());
    }

    boolean currentActorMayManageRole(UUID roleId) {
        return actingAdmin.id().map(actorId -> roleHierarchy.actorMayManageRole(actorId, roleId)).orElse(false);
    }

    boolean currentActorMayManageRoleName(String roleName) {
        return actingAdmin.id()
                .map(actorId -> roleHierarchy.actorMayManageRoleName(actorId, roleName, scope.actingOrg()))
                .orElse(false);
    }

    /** The roles that strictly OUTRANK the actor — hidden from their role listing (empty for a super). */
    Set<UUID> currentRolesAboveActor() {
        return actingAdmin.id().map(roleHierarchy::rolesAboveActor).orElse(Set.of());
    }

    /** Direct permission grants: grantable names only, no platform-only one, and only what the actor holds. */
    boolean mayGrantPermissions(Collection<String> permissions) {
        if (currentIsSuperAdmin()) {
            return true;
        }
        if (permissions == null || permissions.isEmpty()) {
            return true;
        }
        return permissions.stream().allMatch(Permissions::isGrantableName)
                && permissions.stream().noneMatch(Permissions::isPlatformGrant)
                && actingAdmin.authorities().containsAll(permissions);
    }

    /** The non-super ceiling on ONE role, by id. The role's permissions are read ONCE — both of the last two
     *  terms need them, and asking twice meant a second query per role, multiplied by every group in a file. */
    boolean mayGrantRole(UUID actorId, Set<String> actorAuthorities, UUID roleId) {
        if (!roleHierarchy.actorMayManageRole(actorId, roleId)) {
            return false;
        }
        Set<String> granted = roleService.permissionNames(roleId);
        return granted.stream().noneMatch(Permissions::isPlatformGrant) && actorAuthorities.containsAll(granted);
    }

    boolean roleCarriesPlatformPermission(UUID roleId) {
        return roleService.permissionNames(roleId).stream().anyMatch(Permissions::isPlatformGrant);
    }

    /** By name, resolved IN THE ACTING TIER — the role that is checked is the role that gets assigned. */
    boolean roleCarriesPlatformPermission(String roleName) {
        return roleService.findByName(roleName, scope.actingOrg())
                .map(role -> roleCarriesPlatformPermission(role.getId()))
                .orElse(false);
    }

    /** Grant-only-what-you-hold, by id. A super holds the whole catalog, so this is a no-op for them. */
    boolean actorHoldsAllPermissionsOf(UUID roleId) {
        return actingAdmin.authorities().containsAll(roleService.permissionNames(roleId));
    }

    /**
     * The same, by name, resolved in the acting tier. Fails CLOSED on an unknown name: a role the check cannot
     * see must never be assignable — an org-only name resolving as "unknown" while the service happily
     * assigned the org role was a real escalation path.
     */
    boolean actorHoldsAllPermissionsOfRole(String roleName) {
        return roleService.findByName(roleName, scope.actingOrg())
                .map(role -> actorHoldsAllPermissionsOf(role.getId()))
                .orElse(false);
    }

    /** The role's name, or {@code null} if it no longer exists (the caller's service then 404s). */
    String roleName(UUID roleId) {
        return roleService.findById(roleId).map(RoleRef::getName).orElse(null);
    }
}
