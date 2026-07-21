package com.example.sso.admin.internal.role.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.LastAdminGuard;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.shared.IdName;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.rbac.RbacService;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import com.example.sso.user.account.UserAccount;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin operations for roles, their direct members, and the permission catalog (the RBAC building
 * blocks). Delegates role/membership state to the user module via {@link RoleService} — it never
 * touches the {@code Role}/{@code AppUser} entities — and maps the projections to the admin API views.
 */
@Service
@RequiredArgsConstructor
public class RoleAdminService {

    private static final String ADMIN_ROLE = Roles.ADMIN;

    private final RoleService roleService;
    private final RbacService rbacService;
    private final AdminAccessPolicy accessPolicy;
    private final AdminAuditLogger auditLogger;
    private final LastAdminGuard lastAdminGuard;
    private final OrgContext orgContext;
    private final OrgTierGuard tierGuard;

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        // Tier-scoped like every other org-scoped list: a tenant admin sees only its own org's roles, the
        // platform super-admin only the global roles. RLS lets a tenant READ global rows, so the builder must
        // filter in code — otherwise a tenant admin could enumerate (and target) shared/global roles.
        UUID tier = tierGuard.currentTier();
        // Hide-above: a non-super also never sees roles that strictly OUTRANK them within their tier (e.g. a
        // GROUP_ADMIN must not see — nor, via the assignment gates, target — their tenant's ORG_ADMIN). Super
        // sees everything in the global tier.
        Set<UUID> aboveActor = rolesToHideAbove();
        return roleService.findAll().stream()
                .filter(role -> Objects.equals(role.getOrgId(), tier))
                .filter(role -> !aboveActor.contains(role.getId()))
                .map(RoleView::of).toList();
    }

    @Transactional
    public RoleView createRole(String name, Set<String> permissions) {
        // Ceiling: a non-super may put in a role only permissions they themselves hold (grant-only-what-you-
        // hold), and the role is wired BELOW their apex so it sits strictly beneath them — it becomes a role
        // they dominate (and may assign), never one at/above their level. A super holds the whole catalog and
        // creates global roles (empty apex → a detached root they dominate via the super short-circuit).
        requireMayGrantPermissions(permissions);
        Set<UUID> apex = accessPolicy.currentActorApexRoleIds();
        requireWithinApexAuthority(permissions, apex);
        RoleView view = RoleView.of(roleService.create(name, permissions, apex));
        auditLogger.log(AuditType.ROLE_CREATED, "role=" + name + " permissions=" + permissions);
        return view;
    }

    /**
     * Prevents an upward permission bleed: because the new role is wired below the actor's apex and a parent
     * INHERITS its children's permissions, a permission the child carries that the apex (a shared, multi-holder
     * role such as {@code ROLE_ORG_ADMIN}) lacks would silently accrue to every co-holder of that apex. So a
     * created role may carry only permissions the apex ALREADY holds effectively. A super (or an actor with no
     * apex → a detached root with no parent to bleed into) is exempt.
     */
    private void requireWithinApexAuthority(Set<String> permissions, Set<UUID> apex) {
        if (accessPolicy.currentIsSuperAdmin() || apex.isEmpty()) {
            return;
        }
        if (!roleService.effectivePermissionNames(apex).containsAll(permissions)) {
            throw ForbiddenException.of("admin.role.permissionExceedsParent");
        }
    }

    /** A role with its inheritance surfaced: the roles it inherits + its effective (direct ∪ inherited) perms. */
    @Transactional(readOnly = true)
    public RoleDetailView roleDetail(UUID id) {
        requireRoleInTier(id);
        // Hide-above (mirrors listRoles): a non-super must never READ a role that outranks them, or the detail
        // would leak the permissions/inheritance of the level above them (e.g. a sub-admin viewing ORG_ADMIN).
        // Non-revealing 404, identical to a role that is out of tier.
        if (!accessPolicy.isCurrentActorUnscoped() && accessPolicy.currentRolesAboveActor().contains(id)) {
            throw NotFoundException.of("user.role.notFound");
        }
        RoleRef role = roleService.findById(id).orElseThrow(() -> NotFoundException.of("user.role.notFound"));
        List<IdName> inheritsFrom = roleService.idNames(roleService.childRoleIds(id));
        List<IdName> inheritedBy = roleService.idNames(visibleRoleIds(meaningfulParents(id)));
        return RoleDetailView.of(role, inheritsFrom, inheritedBy, roleService.effectivePermissionNames(Set.of(id)));
    }

    /**
     * The parents worth showing as "inherited by": the role's direct parents MINUS the actor's own apex roles.
     * Every role a delegated admin creates is wired below their apex (so the apex dominates and inherits it —
     * see {@code createRole}), so the apex is a parent of virtually every role the actor can see; surfacing it on
     * each one is structural noise, not a meaningful relationship. Dropping it leaves only the deliberate edges
     * (e.g. another custom role that inherits this one).
     */
    private Set<UUID> meaningfulParents(UUID id) {
        Set<UUID> apex = accessPolicy.currentActorApexRoleIds();
        return roleService.parentRoleIds(id).stream()
                .filter(parent -> !apex.contains(parent))
                .collect(Collectors.toSet());
    }

    /**
     * Narrows a set of role ids to those the actor may see (same tier, not strictly above them) — mirrors
     * {@link #listRoles}. Applied to the parents shown as {@code inheritedBy} so surfacing "who inherits this
     * role" can never leak a role above the actor (e.g. a tenant admin viewing a role their {@code ORG_ADMIN}
     * inherits must not learn the {@code ORG_ADMIN} name). A super-admin sees every parent in the global tier.
     */
    private Set<UUID> visibleRoleIds(Set<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        UUID tier = tierGuard.currentTier();
        Set<UUID> aboveActor = rolesToHideAbove();
        // One batched (id, orgId) read instead of a per-parent findById that would hydrate each role's whole
        // permission graph just to read its org. A role absent from the map is one RLS did not surface (excluded).
        Map<UUID, UUID> orgIdById = roleService.orgIdsByIds(roleIds);
        return roleIds.stream()
                .filter(roleId -> !aboveActor.contains(roleId))
                .filter(roleId -> orgIdById.containsKey(roleId) && Objects.equals(orgIdById.get(roleId), tier))
                .collect(Collectors.toSet());
    }

    /** The roles strictly above the actor, hidden from every listing (empty for a super-admin, who sees their tier). */
    private Set<UUID> rolesToHideAbove() {
        return accessPolicy.isCurrentActorUnscoped() ? Set.of() : accessPolicy.currentRolesAboveActor();
    }

    /**
     * Sets the roles this role inherits (its direct children in the DAG). Authorization-critical — a bad edit
     * is a privilege-escalation vector, so it re-asserts every create-time invariant: (1) the role is in the
     * actor's tier and (2) not a system role (system inheritance is locked); (3) a non-super must dominate the
     * role; (4) each chosen child is in the tier; and (5) the actor must hold every permission the ADDED
     * children contribute (grant-only-what-you-hold) — otherwise inheriting them would bleed a permission the
     * actor lacks up into this role's (and its ancestors') holders. The cycle guard and session revocation
     * live in {@code RoleService.setInheritsFrom}.
     */
    @Transactional
    public RoleDetailView setInheritance(UUID id, Set<UUID> childRoleIds) {
        requireRoleInTier(id);
        RoleRef role = roleService.findById(id).orElseThrow(() -> NotFoundException.of("user.role.notFound"));
        if (role.isSystem()) {
            throw ForbiddenException.of("admin.role.systemInheritanceLocked");
        }
        if (!accessPolicy.currentIsSuperAdmin() && !accessPolicy.currentActorMayManageRole(id)) {
            throw ForbiddenException.of("admin.role.notPermitted");
        }
        childRoleIds.forEach(this::requireRoleInTier); // each child must be in the actor's tier (no cross-tier/global)
        Set<UUID> current = roleService.childRoleIds(id);
        Set<UUID> added = childRoleIds.stream().filter(child -> !current.contains(child)).collect(Collectors.toSet());
        if (!added.isEmpty()) {
            requireMayGrantPermissions(roleService.effectivePermissionNames(added));
        }
        try {
            roleService.setInheritsFrom(id, childRoleIds);
        } catch (IllegalStateException cycle) {
            // The DAG cycle guard (the sole multi-hop guard, in RoleHierarchyWriter.link) is a user-triggerable
            // 4xx, not a 500 — a caller chose a child that (transitively, or itself) already inherits this role.
            throw BadRequestException.of("admin.role.inheritanceCycle");
        }
        auditLogger.log(AuditType.ROLE_UPDATED, "role=" + id + " inheritsFrom=" + childRoleIds);
        return roleDetail(id);
    }

    @Transactional
    public RoleView updateRole(UUID id, String name, Set<String> permissions) {
        requireRoleInTier(id);
        // Re-assert the ceiling on every edit: a non-super may edit only a role strictly BELOW them (never a
        // peer/system role such as their own ORG_ADMIN) and may set only permissions they hold — otherwise a
        // tenant admin could rewrite a role to carry an authority they lack, or edit a role at their level.
        if (!accessPolicy.currentIsSuperAdmin()
                && !(accessPolicy.currentActorMayManageRole(id) && accessPolicy.mayGrantPermissions(permissions))) {
            throw ForbiddenException.of("admin.role.notPermitted");
        }
        RoleView view = RoleView.of(roleService.updateRole(id, name, permissions));
        auditLogger.log(AuditType.ROLE_UPDATED, "role=" + id + " name=" + name + " permissions=" + permissions);
        return view;
    }

    private void requireMayGrantPermissions(Set<String> permissions) {
        if (!accessPolicy.mayGrantPermissions(permissions)) {
            throw ForbiddenException.of("admin.role.permissionNotGrantable");
        }
    }

    @Transactional
    public void deleteRole(UUID id) {
        requireRoleInTier(id);
        roleService.deleteRole(id);
        auditLogger.log(AuditType.ROLE_DELETED, "role=" + id);
    }

    /**
     * Confines a role mutation to the actor's own tier. RLS lets a caller READ global/other-tenant rows, so
     * the WRITABLE tier must be enforced in code — identically to every other org-scoped admin service (see
     * {@link OrgTierGuard}). Without this a tenant admin holding {@code role:update}/{@code role:delete} could
     * rewrite the permissions of a GLOBAL/shared role (inherited by every tenant), delete a shared role, or —
     * via the holder-session termination that follows a role edit — force-log-out users across every tenant.
     * {@code orgIdOf} yields empty for a global role and for an unknown id alike, so a tenant admin is denied
     * both; the platform super-admin (tier {@code null}) manages the global roles and must drill into an org
     * to touch that org's roles. The 404 is non-revealing (does not disclose that the role exists).
     */
    private void requireRoleInTier(UUID id) {
        UUID roleTier = roleService.orgIdOf(id).orElse(null);
        if (!Objects.equals(roleTier, tierGuard.currentTier())) {
            throw NotFoundException.of("user.role.notFound");
        }
    }

    /** The users holding this role directly (scope-filtered for a delegated admin). */
    @Transactional(readOnly = true)
    public List<RoleMemberView> roleMembers(UUID roleId) {
        if (roleService.findById(roleId).isEmpty()) {
            throw NotFoundException.of("user.role.notFound");
        }

        List<UserAccount> members = roleService.members(roleId);
        if (accessPolicy.isCurrentActorUnscoped() || accessPolicy.administersBoundOrg()) {
            // Tier-scoped: the role's holders that belong to the ACTING tier — an un-drilled super-admin sees
            // only global holders, a super-admin drilled into a tenant (or a tenant admin) sees that org's
            // holders (a global role has members across tenants — never all merged).
            UUID tier = orgContext.currentOrg().orElse(null);
            members = members.stream().filter(user -> Objects.equals(user.getOrgId(), tier)).toList();
        } else {
            Set<UUID> managed = accessPolicy.currentManagedUserIds();     // resource delegate: subtree
            members = members.stream().filter(user -> managed.contains(user.getId())).toList();
        }
        return members.stream().map(RoleMemberView::of).toList();
    }

    /** Grants a role to a user from the role's member list (privilege/scope enforced by {@code @CanGrantRole}). */
    @Transactional
    public void addRoleMember(UUID roleId, UUID userId) {
        roleService.addMember(roleId, userId);
        auditLogger.log(AuditType.USER_UPDATED, AuditSubjectType.USER, userId.toString(),
                "grant role=" + roleId + " to user=" + userId);
    }

    /** Revokes a role from a user; keeps the last-administrator invariant when the role is {@code ROLE_ADMIN}. */
    @Transactional
    public void removeRoleMember(UUID roleId, UUID userId) {
        if (isAdminRole(roleId)) {
            lastAdminGuard.ensureNotLastAdmin(userId, false);
        }
        roleService.removeMember(roleId, userId);
        auditLogger.log(AuditType.USER_UPDATED, AuditSubjectType.USER, userId.toString(),
                "revoke role=" + roleId + " from user=" + userId);
    }

    private boolean isAdminRole(UUID roleId) {
        return roleService.findById(roleId).map(role -> ADMIN_ROLE.equals(role.getName())).orElse(false);
    }

    /**
     * The permission catalog for the role builder, filtered to the actor's tier: a platform super-admin
     * sees the full catalog; a tenant (org) admin sees only the tenant-grantable subset — platform-only
     * permissions never appear. This is the UI side; {@code RoleServiceImpl} enforces the same split
     * authoritatively on write.
     */
    @Transactional(readOnly = true)
    public List<PermissionView> listPermissions() {
        boolean unscoped = accessPolicy.isCurrentActorUnscoped();
        List<String> catalog = unscoped ? rbacService.allPermissions() : Permissions.tenantGrantable();
        // Hide-above-holdings: a non-super sees only permissions they could actually grant (the same
        // grant-only-what-you-hold rule the write path enforces), so a permission above their level never
        // appears in the builder. A super (unscoped) sees the whole catalog.
        return catalog.stream()
                .filter(permission -> unscoped || accessPolicy.mayGrantPermissions(Set.of(permission)))
                .map(PermissionView::of).toList();
    }
}
