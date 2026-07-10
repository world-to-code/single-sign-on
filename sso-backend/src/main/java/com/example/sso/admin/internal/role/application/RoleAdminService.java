package com.example.sso.admin.internal.role.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.LastAdminGuard;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.Permissions;
import com.example.sso.user.RbacService;
import com.example.sso.user.RoleService;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
        Set<UUID> aboveActor = accessPolicy.isCurrentActorUnscoped()
                ? Set.of() : accessPolicy.currentRolesAboveActor();
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
            throw new ForbiddenException("a created role may not carry a permission its parent role lacks");
        }
    }

    @Transactional
    public RoleView updateRole(UUID id, String name, Set<String> permissions) {
        requireRoleInTier(id);
        // Re-assert the ceiling on every edit: a non-super may edit only a role strictly BELOW them (never a
        // peer/system role such as their own ORG_ADMIN) and may set only permissions they hold — otherwise a
        // tenant admin could rewrite a role to carry an authority they lack, or edit a role at their level.
        if (!accessPolicy.currentIsSuperAdmin()
                && !(accessPolicy.currentActorDominatesRole(id) && accessPolicy.mayGrantPermissions(permissions))) {
            throw new ForbiddenException("not permitted to modify this role");
        }
        RoleView view = RoleView.of(roleService.updateRole(id, name, permissions));
        auditLogger.log(AuditType.ROLE_UPDATED, "role=" + id + " name=" + name + " permissions=" + permissions);
        return view;
    }

    private void requireMayGrantPermissions(Set<String> permissions) {
        if (!accessPolicy.mayGrantPermissions(permissions)) {
            throw new ForbiddenException("not permitted to grant one or more of these permissions");
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
            throw new NotFoundException("role not found");
        }
    }

    /** The users holding this role directly (scope-filtered for a delegated admin). */
    @Transactional(readOnly = true)
    public List<RoleMemberView> roleMembers(UUID roleId) {
        if (roleService.findById(roleId).isEmpty()) {
            throw new NotFoundException("role not found");
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
