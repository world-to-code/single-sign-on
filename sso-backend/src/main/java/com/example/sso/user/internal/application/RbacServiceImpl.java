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
    // a drill-in they are authorized for). Every permission here is tenant-grantable (never a PLATFORM one), and
    // its endpoints are branch-isolated by RLS + OrgTierGuard (a scoped actor's tier is never null, so it cannot
    // touch a global or another tenant's row). This is the single source of truth SHARED by ROLE_ORG_ADMIN and
    // ROLE_CUSTOMER_ADMIN so the two tenant-admin roles never diverge (a divergence would be an isolation bug);
    // the scope difference between them (one org vs all a customer's branches) lives in the drill-in
    // authorization (OrgDrillInFilter/OrganizationAuthorization.canManage), NOT in this set. Grown one capability
    // at a time as each is verified branch-isolated (Workstream C): baseline org read/member-manage + session
    // controls (session policies, network zones) so far.
    private static final List<String> TENANT_ADMIN_PERMISSIONS = List.of(
            Permissions.ORG_READ, Permissions.ORG_MEMBER_MANAGE,
            Permissions.SESSION_POLICY_READ, Permissions.SESSION_POLICY_CREATE,
            Permissions.SESSION_POLICY_UPDATE, Permissions.SESSION_POLICY_DELETE,
            Permissions.NETWORK_ZONE_READ, Permissions.NETWORK_ZONE_CREATE,
            Permissions.NETWORK_ZONE_UPDATE, Permissions.NETWORK_ZONE_DELETE,
            Permissions.SAML_READ, Permissions.SAML_CREATE,
            Permissions.SAML_UPDATE, Permissions.SAML_DELETE,
            Permissions.GROUP_READ, Permissions.GROUP_CREATE,
            Permissions.GROUP_UPDATE, Permissions.GROUP_DELETE);

    private static final List<String> ORG_ADMIN_PERMISSIONS = TENANT_ADMIN_PERMISSIONS;

    private static final List<String> CUSTOMER_ADMIN_PERMISSIONS = TENANT_ADMIN_PERMISSIONS;

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
