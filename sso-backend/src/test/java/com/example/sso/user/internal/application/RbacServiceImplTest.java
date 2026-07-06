package com.example.sso.user.internal.application;

import com.example.sso.user.Permissions;
import com.example.sso.user.Roles;
import com.example.sso.user.internal.domain.Permission;
import com.example.sso.user.internal.domain.PermissionRepository;
import com.example.sso.user.internal.domain.Role;
import com.example.sso.user.internal.domain.RolePermission;
import com.example.sso.user.internal.domain.RolePermissionRepository;
import com.example.sso.user.internal.domain.RoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    void allPermissionsReturnsTheFullCatalog() {
        assertThat(service.allPermissions()).isEqualTo(Permissions.ALL);
    }
}
