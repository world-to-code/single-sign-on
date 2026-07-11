package com.example.sso.user.internal.application;

import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.Roles;
import com.example.sso.user.internal.rbac.domain.Permission;
import com.example.sso.user.internal.rbac.domain.PermissionRepository;
import com.example.sso.user.internal.role.domain.Role;
import com.example.sso.user.internal.rbac.domain.RolePermission;
import com.example.sso.user.internal.rbac.domain.RolePermissionRepository;
import com.example.sso.user.internal.role.domain.RoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RbacServiceImpl}: the super-admin self-heal grants the WHOLE catalog, the scoped
 * group-admin grant is limited to its three baseline permissions, and both fail loudly if the role is
 * missing. Grants are now explicit {@code role_permission} inserts, so the unit's job is to verify one
 * {@link RolePermission} save per permission.
 */
@ExtendWith(MockitoExtension.class)
class RbacServiceImplTest {

    @Mock private PermissionRepository permissions;
    @Mock private RolePermissionRepository rolePermissions;
    @Mock private RoleRepository roles;

    @InjectMocks private RbacServiceImpl service;

    private void permissionsAreGetOrCreated() {
        when(permissions.findByName(anyString())).thenReturn(Optional.empty());
        when(permissions.save(any(Permission.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void grantAllPermissionsToAdminInsertsARowForTheEntireCatalog() {
        when(roles.findByNameAndOrgIdIsNull(Roles.ADMIN)).thenReturn(Optional.of(new Role(Roles.ADMIN)));
        permissionsAreGetOrCreated();

        service.grantAllPermissionsToAdmin();

        verify(rolePermissions, times(Permissions.ALL.size())).save(any(RolePermission.class));
    }

    @Test
    void grantAllPermissionsToAdminFailsWhenTheAdminRoleIsMissing() {
        when(roles.findByNameAndOrgIdIsNull(Roles.ADMIN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantAllPermissionsToAdmin())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void grantGroupAdminPermissionsInsertsOnlyTheThreeBaselineRows() {
        when(roles.findByNameAndOrgIdIsNull(Roles.GROUP_ADMIN)).thenReturn(Optional.of(new Role(Roles.GROUP_ADMIN)));
        permissionsAreGetOrCreated();

        service.grantGroupAdminPermissions();

        verify(rolePermissions, times(3)).save(any(RolePermission.class));
    }

    @Test
    void grantGroupAdminPermissionsFailsWhenTheRoleIsMissing() {
        when(roles.findByNameAndOrgIdIsNull(Roles.GROUP_ADMIN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantGroupAdminPermissions())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void grantOrgAdminPermissionsGrantsTheWholeTenantGrantableCatalog() {
        // A tenant admin manages their whole org: the grant is exactly the tenant-grantable catalog
        // (= ALL minus PLATFORM), so it INCLUDES user/role/policy/app/resource management and EXCLUDES every
        // PLATFORM permission (the registry, portal-settings, cross-tenant audit) — granting one would cross
        // the tenant boundary.
        when(roles.findByNameAndOrgIdIsNull(Roles.ORG_ADMIN))
                .thenReturn(Optional.of(new Role(Roles.ORG_ADMIN)));
        permissionsAreGetOrCreated();

        service.grantOrgAdminPermissions();

        assertThat(grantedPermissionNames())
                .containsExactlyInAnyOrderElementsOf(Permissions.tenantGrantable())
                .contains(Permissions.USER_CREATE, Permissions.ROLE_CREATE, Permissions.POLICY_CREATE,
                        Permissions.APP_ASSIGNMENT_ASSIGN, Permissions.RESOURCE_CREATE, Permissions.CLIENT_CREATE,
                        Permissions.SESSION_POLICY_READ, Permissions.NETWORK_ZONE_READ,
                        Permissions.PORTAL_SETTINGS_UPDATE, // per-tenant admin-console elevation policy
                        Permissions.AUDIT_READ) // org-scoped audit log read
                .doesNotContain(Permissions.ORG_CREATE, Permissions.ORG_DELETE);
    }

    @Test
    void allPermissionsReturnsTheFullCatalog() {
        assertThat(service.allPermissions()).isEqualTo(Permissions.ALL);
    }

    // The names of every permission the grant get-or-created (each maps 1:1 to a role_permission insert).
    private List<String> grantedPermissionNames() {
        ArgumentCaptor<Permission> granted = ArgumentCaptor.forClass(Permission.class);
        verify(permissions, atLeastOnce()).save(granted.capture());
        return granted.getAllValues().stream().map(Permission::getName).toList();
    }
}
