package com.example.sso.admin.internal.role.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.LastAdminGuard;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.Permissions;
import com.example.sso.user.RbacService;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RoleAdminService}: the role member list is scope-filtered for a delegated admin,
 * grant/revoke delegate to the user module and audit, and revoking {@code ROLE_ADMIN} runs the
 * last-administrator invariant (a 409 from {@link LastAdminGuard}) before touching membership.
 */
class RoleAdminServiceTest {

    private RoleService roleService;
    private RbacService rbacService;
    private AdminAccessPolicy accessPolicy;
    private AdminAuditLogger auditLogger;
    private LastAdminGuard lastAdminGuard;
    private RoleAdminService service;

    @BeforeEach
    void setUp() {
        roleService = mock(RoleService.class);
        rbacService = mock(RbacService.class);
        accessPolicy = mock(AdminAccessPolicy.class);
        auditLogger = mock(AdminAuditLogger.class);
        lastAdminGuard = mock(LastAdminGuard.class);
        service = new RoleAdminService(roleService, rbacService, accessPolicy, auditLogger, lastAdminGuard);
    }

    @Test
    void roleMembersAreScopedForADelegatedAdmin() {
        UUID roleId = UUID.randomUUID();
        when(roleService.findById(roleId)).thenReturn(Optional.of(mock(RoleRef.class)));
        UUID managed = UUID.randomUUID();
        UserAccount managedUser = user(managed);
        UserAccount otherUser = user(UUID.randomUUID());
        when(roleService.members(roleId)).thenReturn(List.of(managedUser, otherUser));
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.currentManagedUserIds()).thenReturn(Set.of(managed));

        List<RoleMemberView> members = service.roleMembers(roleId);

        assertThat(members).extracting(RoleMemberView::id).containsExactly(managed.toString());
    }

    @Test
    void roleMembersOfAMissingRoleThrowsNotFound() {
        UUID roleId = UUID.randomUUID();
        when(roleService.findById(roleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.roleMembers(roleId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void addRoleMemberDelegatesAndAudits() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.addRoleMember(roleId, userId);

        verify(roleService).addMember(roleId, userId);
        verify(auditLogger).log(eq(AuditType.USER_UPDATED), eq(AuditSubjectType.USER), eq(userId.toString()), any());
    }

    @Test
    void removeRoleMemberDelegatesAndAuditsForAnOrdinaryRole() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RoleRef ordinary = mock(RoleRef.class);
        when(ordinary.getName()).thenReturn("ROLE_SUPPORT");
        when(roleService.findById(roleId)).thenReturn(Optional.of(ordinary));

        service.removeRoleMember(roleId, userId);

        verify(lastAdminGuard, never()).ensureNotLastAdmin(any(), eq(false));
        verify(roleService).removeMember(roleId, userId);
        verify(auditLogger).log(eq(AuditType.USER_UPDATED), eq(AuditSubjectType.USER), eq(userId.toString()), any());
    }

    @Test
    void revokingTheAdminRoleFromTheLastAdminIsRejectedWith409() {
        UUID userId = UUID.randomUUID();
        UUID adminRoleId = UUID.randomUUID();
        RoleRef adminRole = mock(RoleRef.class);
        when(adminRole.getName()).thenReturn(Roles.ADMIN);
        when(roleService.findById(adminRoleId)).thenReturn(Optional.of(adminRole));
        doThrow(new ConflictException("cannot remove the last administrator"))
                .when(lastAdminGuard).ensureNotLastAdmin(userId, false);

        assertThatThrownBy(() -> service.removeRoleMember(adminRoleId, userId))
                .isInstanceOf(ConflictException.class);
        verify(roleService, never()).removeMember(any(), any());
    }

    @Test
    void listPermissionsGivesASuperAdminTheFullCatalog() {
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(true);
        when(rbacService.allPermissions()).thenReturn(Permissions.ALL);

        assertThat(service.listPermissions()).extracting(PermissionView::name)
                .contains(Permissions.ORG_CREATE, Permissions.PORTAL_SETTINGS_UPDATE, Permissions.USER_READ)
                .hasSize(Permissions.ALL.size());
    }

    @Test
    void listPermissionsHidesPlatformPermissionsFromATenantAdmin() {
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);

        assertThat(service.listPermissions()).extracting(PermissionView::name)
                .doesNotContain(Permissions.ORG_CREATE, Permissions.ORG_DELETE, Permissions.PORTAL_SETTINGS_UPDATE,
                        Permissions.KEY_ROTATE, Permissions.SCIM_MANAGE, Permissions.CLIENT_CREATE, Permissions.AUDIT_READ)
                .contains(Permissions.USER_READ, Permissions.ORG_READ, Permissions.ROLE_CREATE, Permissions.POLICY_READ);
        verify(rbacService, never()).allPermissions(); // a tenant admin uses the static tenant subset
    }

    private UserAccount user(UUID id) {
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(id);
        return account;
    }
}
