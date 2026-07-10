package com.example.sso.user.internal.application;

import com.example.sso.tenancy.OrgContext;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Default {@link RbacService}: manages the permission catalog (PBAC) and its assignment to roles. */
@Service
@RequiredArgsConstructor
@Slf4j
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

    // The per-org baseline: each tenant owns its OWN copies of these system roles (provisioned at org
    // creation), so its admin console manages real, org-owned roles instead of an empty tier-scoped list.
    private static final Map<String, List<String>> BASELINE_ROLES = Map.of(
            Roles.USER, List.of(),
            Roles.GROUP_ADMIN, GROUP_ADMIN_PERMISSIONS,
            Roles.ORG_ADMIN, ORG_ADMIN_PERMISSIONS);

    private final PermissionRepository permissions;
    private final RolePermissionRepository rolePermissions;
    private final RoleRepository roles;
    private final RoleHierarchyWriter roleHierarchy;
    private final OrgContext orgContext;

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
    public Map<String, UUID> provisionBaselineRoles(UUID orgId) {
        // Joins the caller's (org-creating) transaction — REQUIRES_NEW would break the FK to the not-yet-
        // committed org row. The role table is RLS-forced, so the org-scoped writes run in the org's scope
        // (bound onto the held connection) and are flushed INSIDE that scope.
        return orgContext.callInOrg(orgId, () -> {
            Map<String, UUID> provisioned = new LinkedHashMap<>();
            BASELINE_ROLES.forEach((name, baselinePermissions) ->
                    ensureOrgSystemRole(orgId, name, baselinePermissions)
                            .ifPresent(role -> provisioned.put(name, role.getId())));
            wireBaselineHierarchy(orgId, provisioned);
            return provisioned;
        });
    }

    /**
     * Wires this org's inheritance chain {@code ROLE_ORG_ADMIN → ROLE_GROUP_ADMIN → ROLE_USER} plus the
     * cross-tier {@code ROLE_ADMIN(global) → ROLE_ORG_ADMIN(this org)} edge — every edge stamped with THIS
     * org (the child's tenant for the cross-tier one), so it is confined to the tenant by RLS. Only roles
     * actually provisioned (present in {@code provisioned}) are wired: a squatted name that skipped
     * provisioning is never silently connected. Runs inside the org scope opened by the caller.
     */
    private void wireBaselineHierarchy(UUID orgId, Map<String, UUID> provisioned) {
        UUID orgAdmin = provisioned.get(Roles.ORG_ADMIN);
        UUID groupAdmin = provisioned.get(Roles.GROUP_ADMIN);
        UUID user = provisioned.get(Roles.USER);
        if (orgAdmin != null && groupAdmin != null) {
            roleHierarchy.link(orgAdmin, groupAdmin, orgId);
        }
        if (groupAdmin != null && user != null) {
            roleHierarchy.link(groupAdmin, user, orgId);
        }
        if (orgAdmin != null) {
            roles.findByNameAndOrgIdIsNull(Roles.ADMIN)
                    .ifPresent(admin -> roleHierarchy.link(admin.getId(), orgAdmin, orgId));
        }
    }

    @Override
    @Transactional
    public void seedGlobalRoleHierarchy() {
        // The GLOBAL (org NULL) edges may be written only from the platform context (role_hierarchy's tightened
        // WITH CHECK, so a tenant can never mint a global edge). callAsPlatform binds platform='on' onto the
        // held connection — exactly as provisionBaselineRoles binds the tenant scope with callInOrg.
        orgContext.callAsPlatform(() -> {
            UUID admin = globalRoleId(Roles.ADMIN);
            UUID orgAdmin = globalRoleId(Roles.ORG_ADMIN);
            UUID groupAdmin = globalRoleId(Roles.GROUP_ADMIN);
            UUID user = globalRoleId(Roles.USER);
            roleHierarchy.link(admin, orgAdmin, null);
            roleHierarchy.link(orgAdmin, groupAdmin, null);
            roleHierarchy.link(groupAdmin, user, null);
            return null;
        });
    }

    private UUID globalRoleId(String name) {
        return roles.findByNameAndOrgIdIsNull(name)
                .orElseThrow(() -> new IllegalStateException(name + " must exist before seeding the hierarchy"))
                .getId();
    }


    /**
     * Get-or-creates the org's own SYSTEM role and (idempotently) grants its baseline permissions.
     *
     * <p>Fails CLOSED on a name collision: the baseline names are reserved for new roles, but a tenant role
     * predating that reservation could squat one. Adopting it (marking it system + granting the baseline)
     * would silently elevate every current holder to full tenant-admin reach on the next login, so such a
     * role is left untouched and reported — an operator renames it, then provisioning completes.
     */
    private Optional<Role> ensureOrgSystemRole(UUID orgId, String name, List<String> baselinePermissions) {
        Optional<Role> existing = roles.findByNameAndOrgId(name, orgId);
        if (existing.isPresent() && !existing.get().isSystem()) {
            log.error("Organization {} has a non-system role named '{}'; its baseline system role cannot be "
                    + "provisioned until that role is renamed (holders are NOT elevated).", orgId, name);
            return Optional.empty();
        }

        Role role = roles.saveAndFlush(existing.orElseGet(() -> newSystemRole(name, orgId)));
        grantEach(role.getId(), baselinePermissions);
        return Optional.of(role);
    }

    private Role newSystemRole(String name, UUID orgId) {
        Role role = new Role(name, orgId);
        role.markSystem();
        return role;
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
