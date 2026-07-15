package com.example.sso.admin.internal.role.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.LastAdminGuard;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.shared.IdName;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.rbac.RbacService;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import com.example.sso.user.account.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        when(accessPolicy.mayGrantPermissions(any())).thenReturn(true); // holds the whole tenant-grantable set

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
        when(accessPolicy.currentActorMayManageRole(roleId)).thenReturn(true); // at or below the actor (manageable)
        when(accessPolicy.mayGrantPermissions(any())).thenReturn(true);        // holds the permissions set

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
        when(accessPolicy.currentIsSuperAdmin()).thenReturn(true);            // the platform admin is super

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

    @Test
    void listRolesHidesRolesThatOutrankANonSuperActor() {
        // Hide-above (decision 5): a role in the actor's tier that strictly outranks them (e.g. a GROUP_ADMIN
        // seeing their tenant's ORG_ADMIN) is filtered out of the builder entirely — not merely un-assignable.
        UUID org = UUID.randomUUID();
        UUID orgAdminId = UUID.randomUUID();
        RoleRef orgAdmin = roleRef(orgAdminId, Roles.ORG_ADMIN, org); // above the actor, same tier
        RoleRef support = roleRef(UUID.randomUUID(), "ROLE_SUPPORT", org);
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(roleService.findAll()).thenReturn(List.of(orgAdmin, support));
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.currentRolesAboveActor()).thenReturn(Set.of(orgAdminId));

        assertThat(service.listRoles()).extracting(RoleView::name).containsExactly("ROLE_SUPPORT");
    }

    @Test
    void listPermissionsHidesPermissionsATenantAdminDoesNotHold() {
        // A tenant admin sees only permissions they could actually grant (grant-only-what-you-hold); one above
        // their holdings never appears in the builder, aligning the list with the write-path gate.
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.mayGrantPermissions(Set.of(Permissions.USER_READ))).thenReturn(true);
        // every other permission: mayGrantPermissions defaults to false → hidden

        assertThat(service.listPermissions()).extracting(PermissionView::name)
                .containsExactly(Permissions.USER_READ);
    }

    @Test
    void createRoleRejectsPermissionsBeyondTheActorsHoldings() {
        when(accessPolicy.mayGrantPermissions(any())).thenReturn(false); // actor lacks one of the permissions

        assertThatThrownBy(() -> service.createRole("ROLE_X", Set.of(Permissions.SCIM_MANAGE)))
                .isInstanceOf(ForbiddenException.class);
        verify(roleService, never()).create(any(), any(), any());
    }

    @Test
    void createRoleWiresTheNewRoleBelowTheActorsApex() {
        UUID apex = UUID.randomUUID();
        RoleRef created = roleRef(UUID.randomUUID(), "ROLE_X", UUID.randomUUID());
        when(accessPolicy.mayGrantPermissions(any())).thenReturn(true);
        when(accessPolicy.currentActorApexRoleIds()).thenReturn(Set.of(apex));
        when(roleService.effectivePermissionNames(Set.of(apex))).thenReturn(Set.of(Permissions.USER_READ));
        when(roleService.create(eq("ROLE_X"), any(), eq(Set.of(apex)))).thenReturn(created);

        service.createRole("ROLE_X", Set.of(Permissions.USER_READ));

        verify(roleService).create(eq("ROLE_X"), eq(Set.of(Permissions.USER_READ)), eq(Set.of(apex)));
    }

    @Test
    void createRoleRejectsAPermissionTheApexParentRoleDoesNotHold() {
        // The new role is wired below the actor's apex, which INHERITS it. A permission the apex lacks would
        // bleed into that shared role for all its co-holders, so it is refused even though the actor holds it
        // directly (grant-only-what-you-hold passes, the apex-authority cap does not).
        UUID apex = UUID.randomUUID();
        when(accessPolicy.mayGrantPermissions(any())).thenReturn(true);            // actor holds it directly
        when(accessPolicy.currentActorApexRoleIds()).thenReturn(Set.of(apex));
        when(roleService.effectivePermissionNames(Set.of(apex)))
                .thenReturn(Set.of(Permissions.USER_READ));                        // apex lacks key:rotate

        assertThatThrownBy(() -> service.createRole("ROLE_Y", Set.of(Permissions.KEY_ROTATE)))
                .isInstanceOf(ForbiddenException.class);
        verify(roleService, never()).create(any(), any(), any());
    }

    @Test
    void updateRoleRejectsARoleAboveTheActor() {
        // A non-super editing a role that is NOT strictly below them (a peer/system role such as their own
        // ORG_ADMIN) is refused, even though it is in their tier — closes a "same-tier so editable" gap.
        UUID org = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(roleService.orgIdOf(roleId)).thenReturn(Optional.of(org)); // in-tier, passes requireRoleInTier
        when(accessPolicy.currentActorMayManageRole(roleId)).thenReturn(false); // but strictly ABOVE the actor

        assertThatThrownBy(() -> service.updateRole(roleId, "ROLE_ORG_ADMIN", Set.of()))
                .isInstanceOf(ForbiddenException.class);
        verify(roleService, never()).updateRole(any(), any(), any());
    }

    private RoleRef roleRef(UUID id, String name, UUID orgId) {
        RoleRef role = mock(RoleRef.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        when(role.getOrgId()).thenReturn(orgId);
        when(role.getPermissionNames()).thenReturn(Set.of());
        return role;
    }

    @Test
    void roleDetailHidesARoleThatOutranksTheActor() {
        // A non-super must not READ a role above them (mirrors listRoles' hide-above) — the detail would leak
        // that level's permissions/inheritance. Same non-revealing 404 as an out-of-tier role.
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(roleService.orgIdOf(id)).thenReturn(Optional.empty()); // in tier, so we reach the hide-above check
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.currentRolesAboveActor()).thenReturn(Set.of(id));

        assertThatThrownBy(() -> service.roleDetail(id)).isInstanceOf(NotFoundException.class);
        verify(roleService, never()).effectivePermissionNames(any());
    }

    @Test
    void roleDetailHidesAParentThatOutranksTheActorButKeepsAnInTierParent() {
        // "Who inherits this role" = its direct parents, FILTERED to roles the actor may see. BOTH parents are
        // stubbed IN-TIER (orgIdsByIds), so the tier filter never excludes them — the hide-above filter is the
        // ONLY reason parentAbove is dropped, so deleting `!aboveActor.contains(...)` would surface it and fail.
        UUID org = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID visibleParent = UUID.randomUUID();
        UUID parentAbove = UUID.randomUUID();
        RoleRef theRole = roleRef(id, "SUPPORT", org);
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(roleService.orgIdOf(id)).thenReturn(Optional.of(org));
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.currentRolesAboveActor()).thenReturn(Set.of(parentAbove)); // parentAbove outranks the actor
        when(roleService.findById(id)).thenReturn(Optional.of(theRole));
        when(roleService.childRoleIds(id)).thenReturn(Set.of());
        when(roleService.parentRoleIds(id)).thenReturn(Set.of(visibleParent, parentAbove));
        when(roleService.orgIdsByIds(any())).thenReturn(Map.of(visibleParent, org, parentAbove, org)); // both in-tier
        IdName visibleParentName = mock(IdName.class);
        when(visibleParentName.getName()).thenReturn("APP_MANAGER");
        when(roleService.idNames(Set.of(visibleParent))).thenReturn(List.of(visibleParentName));
        when(roleService.effectivePermissionNames(Set.of(id))).thenReturn(Set.of("ticket:read"));

        RoleDetailView view = service.roleDetail(id);

        assertThat(view.inheritedBy()).extracting(IdName::getName).containsExactly("APP_MANAGER");
    }

    @Test
    void roleDetailSurfacesAnInTierParentWhenNothingOutranksTheActor() {
        // The positive companion to the test above: with an empty hide-above set the SAME in-tier parent surfaces,
        // proving it is hide-above — not the tier filter — that removes parentAbove there.
        UUID org = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        RoleRef theRole = roleRef(id, "SUPPORT", org);
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(roleService.orgIdOf(id)).thenReturn(Optional.of(org));
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.currentRolesAboveActor()).thenReturn(Set.of()); // nothing above the actor
        when(roleService.findById(id)).thenReturn(Optional.of(theRole));
        when(roleService.childRoleIds(id)).thenReturn(Set.of());
        when(roleService.parentRoleIds(id)).thenReturn(Set.of(parent));
        when(roleService.orgIdsByIds(any())).thenReturn(Map.of(parent, org));
        IdName parentName = mock(IdName.class);
        when(parentName.getName()).thenReturn("OPS");
        when(roleService.idNames(Set.of(parent))).thenReturn(List.of(parentName));
        when(roleService.effectivePermissionNames(Set.of(id))).thenReturn(Set.of("ticket:read"));

        RoleDetailView view = service.roleDetail(id);

        assertThat(view.inheritedBy()).extracting(IdName::getName).containsExactly("OPS");
    }

    @Test
    void roleDetailDropsAParentInADifferentTier() {
        // A parent owned by a DIFFERENT org (and not above the actor) is excluded by the tier filter — pins that
        // the org comparison, independent of hide-above, does the work.
        UUID org = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID foreignParent = UUID.randomUUID();
        RoleRef theRole = roleRef(id, "SUPPORT", org);
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(roleService.orgIdOf(id)).thenReturn(Optional.of(org));
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.currentRolesAboveActor()).thenReturn(Set.of());
        when(roleService.findById(id)).thenReturn(Optional.of(theRole));
        when(roleService.childRoleIds(id)).thenReturn(Set.of());
        when(roleService.parentRoleIds(id)).thenReturn(Set.of(foreignParent));
        when(roleService.orgIdsByIds(any())).thenReturn(Map.of(foreignParent, otherOrg)); // a different tier
        when(roleService.effectivePermissionNames(Set.of(id))).thenReturn(Set.of("ticket:read"));

        RoleDetailView view = service.roleDetail(id);

        assertThat(view.inheritedBy()).isEmpty();
    }

    @Test
    void roleDetailForASuperAdminShowsGlobalParentsWithoutConsultingHideAbove() {
        // A platform super-admin (unscoped, tier = null) sees parents in the global tier, and the hide-above set
        // is never consulted — pins the unscoped branch of the visibility filter.
        UUID id = UUID.randomUUID();
        UUID globalParent = UUID.randomUUID();
        RoleRef theRole = roleRef(id, "ROLE_GROUP_ADMIN", null);
        Map<UUID, UUID> globalTier = new HashMap<>();
        globalTier.put(globalParent, null); // a global parent (orgId null); Map.of rejects null values
        when(orgContext.currentOrg()).thenReturn(Optional.empty()); // platform → tier null
        when(roleService.orgIdOf(id)).thenReturn(Optional.empty()); // global role, in the global tier
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(true);
        when(roleService.findById(id)).thenReturn(Optional.of(theRole));
        when(roleService.childRoleIds(id)).thenReturn(Set.of());
        when(roleService.parentRoleIds(id)).thenReturn(Set.of(globalParent));
        when(roleService.orgIdsByIds(any())).thenReturn(globalTier);
        IdName parentName = mock(IdName.class);
        when(parentName.getName()).thenReturn("ROLE_ORG_ADMIN");
        when(roleService.idNames(Set.of(globalParent))).thenReturn(List.of(parentName));
        when(roleService.effectivePermissionNames(Set.of(id))).thenReturn(Set.of("x"));

        RoleDetailView view = service.roleDetail(id);

        verify(accessPolicy, never()).currentRolesAboveActor();
        assertThat(view.inheritedBy()).extracting(IdName::getName).containsExactly("ROLE_ORG_ADMIN");
    }

    @Test
    void roleDetailOmitsTheActorsApexFromTheInheritedByList() {
        // Every role a delegated admin creates is wired below their apex, so the apex inherits nearly every role.
        // BOTH parents are stubbed IN-TIER and nothing is above the actor, so meaningfulParents (apex subtraction)
        // is the ONLY reason apex is dropped — removing it would surface apex and fail this assertion.
        UUID org = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID apex = UUID.randomUUID();
        UUID otherParent = UUID.randomUUID();
        RoleRef theRole = roleRef(id, "SUPPORT", org);
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(roleService.orgIdOf(id)).thenReturn(Optional.of(org));
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.currentRolesAboveActor()).thenReturn(Set.of());
        when(accessPolicy.currentActorApexRoleIds()).thenReturn(Set.of(apex));
        when(roleService.findById(id)).thenReturn(Optional.of(theRole));
        when(roleService.childRoleIds(id)).thenReturn(Set.of());
        when(roleService.parentRoleIds(id)).thenReturn(Set.of(apex, otherParent));
        when(roleService.orgIdsByIds(any())).thenReturn(Map.of(apex, org, otherParent, org)); // both in-tier
        IdName otherParentName = mock(IdName.class);
        when(otherParentName.getName()).thenReturn("PLATFORM_OPS");
        when(roleService.idNames(Set.of(otherParent))).thenReturn(List.of(otherParentName));
        when(roleService.effectivePermissionNames(Set.of(id))).thenReturn(Set.of("ticket:read"));

        RoleDetailView view = service.roleDetail(id);

        assertThat(view.inheritedBy()).extracting(IdName::getName).containsExactly("PLATFORM_OPS");
    }

    // --- setInheritance: the authorization gates on editing a role's inheritance ---

    private RoleRef customRole(UUID id) {
        RoleRef role = mock(RoleRef.class);
        when(role.isSystem()).thenReturn(false);
        return role;
    }

    @Test
    void setInheritanceRejectsASystemRole() {
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(roleService.orgIdOf(id)).thenReturn(Optional.empty()); // in the (global) tier
        RoleRef role = mock(RoleRef.class);
        when(role.isSystem()).thenReturn(true);
        when(roleService.findById(id)).thenReturn(Optional.of(role));

        assertThatThrownBy(() -> service.setInheritance(id, Set.of(UUID.randomUUID())))
                .isInstanceOf(ForbiddenException.class);
        verify(roleService, never()).setInheritsFrom(any(), any());
    }

    @Test
    void setInheritanceRejectsARoleOutsideTheActorsTier() {
        UUID id = UUID.randomUUID();
        UUID tier = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(tier));
        when(roleService.orgIdOf(id)).thenReturn(Optional.empty()); // a global role — not in the tenant's tier

        assertThatThrownBy(() -> service.setInheritance(id, Set.of())).isInstanceOf(NotFoundException.class);
        verify(roleService, never()).setInheritsFrom(any(), any());
    }

    @Test
    void setInheritanceRejectsANonSuperWhoDoesNotDominateTheRole() {
        UUID id = UUID.randomUUID();
        UUID tier = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(tier));
        when(roleService.orgIdOf(id)).thenReturn(Optional.of(tier));
        RoleRef role = customRole(id);
        when(roleService.findById(id)).thenReturn(Optional.of(role));
        when(accessPolicy.currentIsSuperAdmin()).thenReturn(false);
        when(accessPolicy.currentActorMayManageRole(id)).thenReturn(false);

        assertThatThrownBy(() -> service.setInheritance(id, Set.of())).isInstanceOf(ForbiddenException.class);
        verify(roleService, never()).setInheritsFrom(any(), any());
    }

    @Test
    void setInheritanceRejectsAChildOutsideTheTier() {
        UUID id = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID tier = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(tier));
        when(roleService.orgIdOf(id)).thenReturn(Optional.of(tier));
        RoleRef role = customRole(id);
        when(roleService.findById(id)).thenReturn(Optional.of(role));
        when(accessPolicy.currentIsSuperAdmin()).thenReturn(false);
        when(accessPolicy.currentActorMayManageRole(id)).thenReturn(true);
        when(roleService.orgIdOf(child)).thenReturn(Optional.empty()); // child not in the tenant's tier

        assertThatThrownBy(() -> service.setInheritance(id, Set.of(child))).isInstanceOf(NotFoundException.class);
        verify(roleService, never()).setInheritsFrom(any(), any());
    }

    @Test
    void setInheritanceRejectsAddingAChildWhosePermissionsTheActorLacks() {
        UUID id = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID tier = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(tier));
        when(roleService.orgIdOf(id)).thenReturn(Optional.of(tier));
        when(roleService.orgIdOf(child)).thenReturn(Optional.of(tier));
        RoleRef role = customRole(id);
        when(roleService.findById(id)).thenReturn(Optional.of(role));
        when(accessPolicy.currentIsSuperAdmin()).thenReturn(false);
        when(accessPolicy.currentActorMayManageRole(id)).thenReturn(true);
        when(roleService.childRoleIds(id)).thenReturn(Set.of());
        when(roleService.effectivePermissionNames(Set.of(child))).thenReturn(Set.of("user:delete"));
        when(accessPolicy.mayGrantPermissions(Set.of("user:delete"))).thenReturn(false);

        assertThatThrownBy(() -> service.setInheritance(id, Set.of(child))).isInstanceOf(ForbiddenException.class);
        verify(roleService, never()).setInheritsFrom(any(), any());
    }

    @Test
    void setInheritanceAppliesWhenTheActorMayManageTheRoleAndHoldsTheChildsPermissions() {
        UUID id = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID tier = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(tier));
        when(roleService.orgIdOf(id)).thenReturn(Optional.of(tier));
        when(roleService.orgIdOf(child)).thenReturn(Optional.of(tier));
        RoleRef role = customRole(id);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn("SUPPORT");
        when(role.getPermissionNames()).thenReturn(Set.of("ticket:read"));
        when(roleService.findById(id)).thenReturn(Optional.of(role));
        when(accessPolicy.currentIsSuperAdmin()).thenReturn(false);
        when(accessPolicy.currentActorMayManageRole(id)).thenReturn(true);
        when(roleService.childRoleIds(id)).thenReturn(Set.of());
        when(roleService.effectivePermissionNames(Set.of(child))).thenReturn(Set.of("user:delete"));
        when(accessPolicy.mayGrantPermissions(Set.of("user:delete"))).thenReturn(true);
        when(roleService.effectivePermissionNames(Set.of(id))).thenReturn(Set.of("ticket:read", "user:delete"));
        when(roleService.idNames(any())).thenReturn(List.of());

        RoleDetailView view = service.setInheritance(id, Set.of(child));

        verify(roleService).setInheritsFrom(id, Set.of(child));
        verify(auditLogger).log(eq(AuditType.ROLE_UPDATED), any());
        // The A-holder now effectively carries the inherited permission (e.g. user:delete via the child).
        assertThat(view.effectivePermissions()).contains("user:delete");
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
