package com.example.sso.admin.internal.role.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.LastAdminGuard;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.Permissions;
import com.example.sso.user.RbacService;
import com.example.sso.user.RoleService;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import java.util.List;
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

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return roleService.findAll().stream().map(RoleView::of).toList();
    }

    @Transactional
    public RoleView createRole(String name, Set<String> permissions) {
        RoleView view = RoleView.of(roleService.create(name, permissions));
        auditLogger.log(AuditType.ROLE_CREATED, "role=" + name + " permissions=" + permissions);
        return view;
    }

    @Transactional
    public RoleView updateRole(UUID id, String name, Set<String> permissions) {
        RoleView view = RoleView.of(roleService.updateRole(id, name, permissions));
        auditLogger.log(AuditType.ROLE_UPDATED, "role=" + id + " name=" + name + " permissions=" + permissions);
        return view;
    }

    @Transactional
    public void deleteRole(UUID id) {
        roleService.deleteRole(id);
        auditLogger.log(AuditType.ROLE_DELETED, "role=" + id);
    }

    /** The users holding this role directly (scope-filtered for a delegated admin). */
    @Transactional(readOnly = true)
    public List<RoleMemberView> roleMembers(UUID roleId) {
        if (roleService.findById(roleId).isEmpty()) {
            throw new NotFoundException("role not found");
        }

        List<UserAccount> members = roleService.members(roleId);
        if (!accessPolicy.isCurrentActorUnscoped()) {
            Set<UUID> managed = accessPolicy.currentManagedUserIds();
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
        List<String> catalog = accessPolicy.isCurrentActorUnscoped()
                ? rbacService.allPermissions()
                : Permissions.tenantGrantable();
        return catalog.stream().map(PermissionView::of).toList();
    }
}
