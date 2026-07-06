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

    // Baseline for the scoped ROLE_ORG_ADMIN: read their org and manage its membership. Deliberately
    // minimal until org-scoped authorization lands — no directory-wide or org create/delete authority.
    private static final List<String> ORG_ADMIN_PERMISSIONS = List.of(
            Permissions.ORG_READ, Permissions.ORG_MEMBER_MANAGE);

    // Baseline for the scoped ROLE_CUSTOMER_ADMIN: manage the branches under their customer, exactly like an
    // org-admin manages an org (read + member-manage, scoped to their branches by customer membership).
    // Deliberately EXCLUDES every PLATFORM permission — no customer:* registry authority, no org create/delete
    // — so a customer-admin can never mutate the registry nor cross into another customer.
    private static final List<String> CUSTOMER_ADMIN_PERMISSIONS = List.of(
            Permissions.ORG_READ, Permissions.ORG_MEMBER_MANAGE);

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
    @Transactional
    public void grantCustomerAdminPermissions() {
        Role customerAdmin = roles.findByNameAndOrgIdIsNull(Roles.CUSTOMER_ADMIN)
                .orElseThrow(() -> new IllegalStateException("ROLE_CUSTOMER_ADMIN must exist before granting permissions"));

        grantEach(customerAdmin.getId(), CUSTOMER_ADMIN_PERMISSIONS);
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
