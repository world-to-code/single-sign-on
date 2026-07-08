package com.example.sso.user.internal.application;

import com.example.sso.user.Roles;
import com.example.sso.user.internal.domain.Permission;
import com.example.sso.user.Permissions;
import com.example.sso.user.RbacService;
import com.example.sso.user.internal.domain.Role;
import com.example.sso.user.internal.domain.RolePermission;
import com.example.sso.user.internal.domain.RolePermissionId;
import com.example.sso.user.internal.domain.RolePermissionRepository;
import com.example.sso.user.internal.domain.RoleRepository;
import com.example.sso.user.internal.domain.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Default {@link RbacService}: manages the permission catalog (PBAC) and its assignment to roles. */
@Service
@RequiredArgsConstructor
public class RbacServiceImpl implements RbacService {

    /** The fine-grained permissions used by method-level {@code @PreAuthorize} policies (see {@link Permissions}). */
    private static final List<String> ALL_PERMISSIONS = Permissions.ALL;

    // Baseline permissions for the scoped ROLE_GROUP_ADMIN: read/update/delete the users they manage.
    // Deliberately excludes user:create (super-only, see AdminAccessPolicy.canCreateUser) and group:read
    // (which is unscoped and would let a scoped admin enumerate the whole directory).
    private static final List<String> GROUP_ADMIN_PERMISSIONS = List.of(
            Permissions.USER_READ, Permissions.USER_UPDATE, Permissions.USER_DELETE);

    // What a tenant admin may manage WITHIN their own org (bound by OrgContextFilter to their login org, or by
    // a drill-in they are authorized for): the WHOLE tenant-grantable catalog (= ALL minus the PLATFORM set).
    // Every one of these is org-isolated — either by RLS + OrgTierGuard (a scoped actor's tier is never null,
    // so it cannot touch a global or another tenant's row: role/auth-policy/session-policy/network-zone/saml-rp/
    // app-assignment/resource), by host-org scoping (oidc-client via OrgScopedRegisteredClientRepository), or by
    // an app-layer org check (user:* — app_user has no RLS, scoped in AdminAccessPolicy/UserAdminService). The
    // PLATFORM permissions (organization registry, portal-settings, cross-tenant audit) are excluded. Sourced
    // from Permissions.tenantGrantable() so it self-maintains as the PLATFORM classification evolves.
    private static final List<String> ORG_ADMIN_PERMISSIONS = Permissions.tenantGrantable();

    private final PermissionRepository permissions;
    private final RolePermissionRepository rolePermissions;
    private final RoleRepository roles;

    @Override
    @Transactional
    public void grantAllPermissionsToAdmin() {
        Role admin = roles.findByNameAndOrgIdIsNull(Roles.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ROLE_ADMIN must exist before granting permissions"));

        grantEach(admin.getId(), ALL_PERMISSIONS);
    }

    @Override
    @Transactional
    public void grantGroupAdminPermissions() {
        Role groupAdmin = roles.findByNameAndOrgIdIsNull(Roles.GROUP_ADMIN)
                .orElseThrow(() -> new IllegalStateException("ROLE_GROUP_ADMIN must exist before granting permissions"));

        grantEach(groupAdmin.getId(), GROUP_ADMIN_PERMISSIONS);
    }

    @Override
    @Transactional
    public void grantOrgAdminPermissions() {
        Role orgAdmin = roles.findByNameAndOrgIdIsNull(Roles.ORG_ADMIN)
                .orElseThrow(() -> new IllegalStateException("ROLE_ORG_ADMIN must exist before granting permissions"));

        grantEach(orgAdmin.getId(), ORG_ADMIN_PERMISSIONS);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> allPermissions() {
        return ALL_PERMISSIONS;
    }

    /** Inserts an explicit {@code role_permission} row per permission (get-or-created), idempotently. */
    private void grantEach(UUID roleId, List<String> permissionNames) {
        permissionNames.forEach(name -> {
            UUID permissionId = getOrCreatePermission(name).getId();
            if (!rolePermissions.existsById(new RolePermissionId(roleId, permissionId))) {
                rolePermissions.save(new RolePermission(roleId, permissionId));
            }
        });
    }

    private Permission getOrCreatePermission(String name) {
        return permissions.findByName(name).orElseGet(() -> permissions.save(new Permission(name)));
    }
}
