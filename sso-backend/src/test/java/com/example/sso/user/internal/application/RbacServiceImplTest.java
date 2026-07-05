package com.example.sso.user.internal.application;

import com.example.sso.user.Permissions;
import com.example.sso.user.Roles;
import com.example.sso.user.internal.domain.Permission;
import com.example.sso.user.internal.domain.PermissionRepository;
import com.example.sso.user.internal.domain.Role;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RbacServiceImpl}: the super-admin self-heal grants the WHOLE catalog, the
 * scoped group-admin grant is limited to its three baseline permissions, and both fail loudly if the
 * role is missing. The grant + save interaction is the unit's job, so {@code verify(...)} the save.
 */
@ExtendWith(MockitoExtension.class)
class RbacServiceImplTest {

    @Mock private PermissionRepository permissions;
    @Mock private RoleRepository roles;

    @InjectMocks private RbacServiceImpl service;

    private void permissionsAreGetOrCreated() {
        when(permissions.findByName(anyString())).thenReturn(Optional.empty());
        when(permissions.save(any(Permission.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void grantAllPermissionsToAdminAssignsTheEntireCatalogAndSaves() {
        Role admin = new Role(Roles.ADMIN);
        when(roles.findByNameAndOrgIdIsNull(Roles.ADMIN)).thenReturn(Optional.of(admin));
        permissionsAreGetOrCreated();

        service.grantAllPermissionsToAdmin();

        assertThat(admin.getPermissionNames()).containsExactlyInAnyOrderElementsOf(Permissions.ALL);
        verify(roles).save(admin);
    }

    @Test
    void grantAllPermissionsToAdminFailsWhenTheAdminRoleIsMissing() {
        when(roles.findByNameAndOrgIdIsNull(Roles.ADMIN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantAllPermissionsToAdmin())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void grantGroupAdminPermissionsAssignsOnlyTheThreeBaselinePermissions() {
        Role groupAdmin = new Role(Roles.GROUP_ADMIN);
        when(roles.findByNameAndOrgIdIsNull(Roles.GROUP_ADMIN)).thenReturn(Optional.of(groupAdmin));
        permissionsAreGetOrCreated();

        service.grantGroupAdminPermissions();

        assertThat(groupAdmin.getPermissionNames()).containsExactlyInAnyOrder(
                Permissions.USER_READ, Permissions.USER_UPDATE, Permissions.USER_DELETE);
        verify(roles).save(groupAdmin);
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
