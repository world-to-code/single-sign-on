package com.example.sso.admin.internal.role.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.LastAdminGuard;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
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
    private OrgContext orgContext;
    private OrgTierGuard tierGuard;
    private RoleAdminService service;

    @BeforeEach
    void setUp() {
        roleService = mock(RoleService.class);
        rbacService = mock(RbacService.class);
        accessPolicy = mock(AdminAccessPolicy.class);
        auditLogger = mock(AdminAuditLogger.class);
        lastAdminGuard = mock(LastAdminGuard.class);
        orgContext = mock(OrgContext.class);
        tierGuard = new OrgTierGuard(orgContext);
        service = new RoleAdminService(
                roleService, rbacService, accessPolicy, auditLogger, lastAdminGuard, orgContext, tierGuard);
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
    void aTenantAdminSeesOnlyTheirOwnOrgsMembersOfARole() {
        // A global role has holders across tenants; a tenant admin sees ONLY their org's holders (not another
        // tenant's same-role members), so they can manage role membership within their own org.
        UUID roleId = UUID.randomUUID();
        when(roleService.findById(roleId)).thenReturn(Optional.of(mock(RoleRef.class)));
        UUID org = UUID.randomUUID();
        UserAccount mine = userInOrg(UUID.randomUUID(), org);
        UserAccount theirs = userInOrg(UUID.randomUUID(), UUID.randomUUID());
        when(roleService.members(roleId)).thenReturn(List.of(mine, theirs));
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.administersBoundOrg()).thenReturn(true);
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));

        assertThat(service.roleMembers(roleId)).extracting(RoleMemberView::id)
                .containsExactly(mine.getId().toString());
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
                .doesNotContain(Permissions.ORG_CREATE, Permissions.ORG_DELETE, Permissions.ORG_UPDATE)
                .contains(Permissions.USER_READ, Permissions.ORG_READ, Permissions.ROLE_CREATE, Permissions.POLICY_READ,
                        Permissions.AUDIT_READ, // org-scoped audit log read -> tenant-grantable
                        Permissions.PORTAL_SETTINGS_UPDATE, // per-tenant admin-console policy
                        Permissions.CLIENT_CREATE, // host-org-scoped OIDC clients -> tenant-grantable
                        Permissions.SCIM_MANAGE); // /Users org-scoped -> tenant-grantable
        verify(rbacService, never()).allPermissions(); // a tenant admin uses the static tenant subset
    }

    @Test
    void aTenantAdminCannotUpdateAGlobalRole() {
        // The core cross-tenant isolation guard: a tenant admin holding role:update must not be able to rewrite
        // the permissions of a GLOBAL/shared role (org_id null) — that role's authorities are inherited by every
        // tenant, and the holder-session termination that follows a role edit would span all tenants.
        UUID globalRoleId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(UUID.randomUUID())); // acting as a tenant admin
        when(roleService.orgIdOf(globalRoleId)).thenReturn(Optional.empty());     // global role — no owning org

        assertThatThrownBy(() -> service.updateRole(globalRoleId, "ROLE_USER", Set.of(Permissions.USER_READ)))
                .isInstanceOf(NotFoundException.class);
        verify(roleService, never()).updateRole(any(), any(), any());
    }

    @Test
    void aTenantAdminCannotDeleteAGlobalRole() {
        UUID globalRoleId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(UUID.randomUUID()));
        when(roleService.orgIdOf(globalRoleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteRole(globalRoleId)).isInstanceOf(NotFoundException.class);
        verify(roleService, never()).deleteRole(any());
    }

    @Test
    void aTenantAdminCannotEditAnotherTenantsRole() {
        UUID otherOrgRoleId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(UUID.randomUUID()));
        when(roleService.orgIdOf(otherOrgRoleId)).thenReturn(Optional.of(UUID.randomUUID())); // a different org

        assertThatThrownBy(() -> service.updateRole(otherOrgRoleId, "x", Set.of()))
                .isInstanceOf(NotFoundException.class);
        verify(roleService, never()).updateRole(any(), any(), any());
    }

    @Test
    void aTenantAdminCanUpdateItsOwnRole() {
        UUID org = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        RoleRef updated = roleRef(roleId, "ROLE_SUPPORT", org);
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(roleService.orgIdOf(roleId)).thenReturn(Optional.of(org));
        when(roleService.updateRole(eq(roleId), eq("ROLE_SUPPORT"), any())).thenReturn(updated);

        RoleView view = service.updateRole(roleId, "ROLE_SUPPORT", Set.of(Permissions.USER_READ));

        assertThat(view.id()).isEqualTo(roleId.toString());
        verify(roleService).updateRole(eq(roleId), eq("ROLE_SUPPORT"), any());
        verify(auditLogger).log(eq(AuditType.ROLE_UPDATED), any());
    }

    @Test
    void thePlatformAdminManagesGlobalRoles() {
        UUID globalRoleId = UUID.randomUUID();
        RoleRef updated = roleRef(globalRoleId, "ROLE_SUPPORT", null);
        when(orgContext.currentOrg()).thenReturn(Optional.empty());           // platform tier (null)
        when(roleService.orgIdOf(globalRoleId)).thenReturn(Optional.empty()); // global role
        when(roleService.updateRole(eq(globalRoleId), any(), any())).thenReturn(updated);

        service.updateRole(globalRoleId, "ROLE_SUPPORT", Set.of());

        verify(roleService).updateRole(eq(globalRoleId), any(), any());
    }

    @Test
    void listRolesIsScopedToTheActingTenant() {
        UUID org = UUID.randomUUID();
        RoleRef mine = roleRef(UUID.randomUUID(), "ROLE_SUPPORT", org);
        RoleRef global = roleRef(UUID.randomUUID(), Roles.ADMIN, null); // must not leak to a tenant admin
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(roleService.findAll()).thenReturn(List.of(mine, global));

        assertThat(service.listRoles()).extracting(RoleView::name).containsExactly("ROLE_SUPPORT");
    }

    @Test
    void listRolesForThePlatformAdminReturnsGlobalRoles() {
        RoleRef tenantRole = roleRef(UUID.randomUUID(), "ROLE_SUPPORT", UUID.randomUUID());
        RoleRef global = roleRef(UUID.randomUUID(), Roles.ADMIN, null);
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(roleService.findAll()).thenReturn(List.of(tenantRole, global));

        assertThat(service.listRoles()).extracting(RoleView::name).containsExactly(Roles.ADMIN);
    }

    private RoleRef roleRef(UUID id, String name, UUID orgId) {
        RoleRef role = mock(RoleRef.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        when(role.getOrgId()).thenReturn(orgId);
        when(role.getPermissionNames()).thenReturn(Set.of());
        return role;
    }

    private UserAccount user(UUID id) {
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(id);
        return account;
    }

    private UserAccount userInOrg(UUID id, UUID orgId) {
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.getOrgId()).thenReturn(orgId);
        return account;
    }
}
