package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.ActingAdminTier;
import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.LastAdminGuard;
import com.example.sso.admin.internal.shared.application.MembershipDenyCeiling;
import com.example.sso.organization.OrganizationService;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.shared.error.BadRequestException;
import java.util.List;
import java.util.Map;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.role.Roles;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.account.UserUpdate;
import com.example.sso.user.role.RoleRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserAdminService}. Focus: directory pages are scoped in the DB by the access
 * policy, the actor-independent last-administrator invariant (delegated to {@link LastAdminGuard})
 * rejects with a 409, a domain {@code IllegalArgument} is surfaced as a 409, and the mutating operations
 * write the audit trail (verify interactions).
 */
class UserAdminServiceTest {

    private UserService userService;
    private AdminAccessPolicy accessPolicy;
    private AdminAuditLogger auditLogger;
    private LastAdminGuard lastAdminGuard;
    private ActingAdminTier tier;
    private MembershipDenyCeiling membershipDenies;

    private UserAdminService service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        accessPolicy = mock(AdminAccessPolicy.class);
        auditLogger = mock(AdminAuditLogger.class);
        lastAdminGuard = mock(LastAdminGuard.class);
        tier = mock(ActingAdminTier.class);
        membershipDenies = mock(MembershipDenyCeiling.class);
        service = new UserAdminService(userService, tier, accessPolicy, auditLogger, lastAdminGuard,
                membershipDenies);
    }

    @Test
    void anUnDrilledPlatformAdminSeesOnlyGlobalUsersNotEveryTenantMerged() {
        // Drill-in scoping: a super-admin who has NOT drilled into a tenant (tier null) sees only the global
        // (org-less) users — they drill into a tenant to see ITS users, never all tenants merged.
        UserAccount global = user(UUID.randomUUID());
        when(tier.administersWholeTier()).thenReturn(true); // not drilled
        when(userService.findByOrg(null, 0, 20)).thenReturn(new Page<>(1, 0, 20, List.of(global)));

        assertThat(service.listUsers(0, 20).items()).hasSize(1);
        verify(accessPolicy, never()).currentManagedUserIds();
        verify(userService, never()).findByIds(any(), anyInt(), anyInt());
    }

    @Test
    void scopedActorPagesOnlyTheManagedIdsInTheDatabase() {
        UUID managed = UUID.randomUUID();
        when(tier.administersWholeTier()).thenReturn(false);
        when(accessPolicy.currentManagedUserIds()).thenReturn(Set.of(managed));
        UserAccount managedUser = user(managed);
        when(userService.findByIds(Set.of(managed), 0, 20)).thenReturn(new Page<>(1, 0, 20, List.of(managedUser)));

        Page<AdminUserView> result = service.listUsers(0, 20);

        assertThat(result.items()).extracting(AdminUserView::id).containsExactly(managed.toString());
    }

    @Test
    void deletingTheLastAdminIsRejectedWith409() {
        UUID targetId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UserAccount target = mock(UserAccount.class);
        when(target.getOrgId()).thenReturn(orgId);
        when(userService.findById(targetId)).thenReturn(Optional.of(target));
        doThrow(new ConflictException("cannot remove the last administrator"))
                .when(lastAdminGuard).ensureTierRetainsAdmin(orgId);

        assertThatThrownBy(() -> service.deleteUser(targetId)).isInstanceOf(ConflictException.class);
        // Mutate-then-guard: the delete is issued, then the 409 rolls the transaction back (proven in the IT).
        verify(userService).delete(targetId);
        verify(auditLogger, never()).log(any(), any(), any(), any());
    }







    @Test
    void deleteUserDelegatesAndAuditsWhenNotTheLastAdmin() {
        UUID targetId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UserAccount target = mock(UserAccount.class);
        when(target.getOrgId()).thenReturn(orgId);
        when(userService.findById(targetId)).thenReturn(Optional.of(target));

        service.deleteUser(targetId);

        verify(lastAdminGuard).ensureTierRetainsAdmin(orgId);
        verify(userService).delete(targetId);
        verify(auditLogger).log(eq(AuditType.USER_DELETED), eq(AuditSubjectType.USER), any(), any());
    }

    @Test
    void updatingAUserRecountsThatUsersTierAfterTheChange() {
        UUID id = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UserAccount updated = user(id);
        when(updated.getOrgId()).thenReturn(orgId);
        when(userService.updateUser(eq(id), any())).thenReturn(updated);

        service.updateUser(id, new UserUpdate("New Name", "e@example.com", true, Set.of()));

        verify(lastAdminGuard).ensureTierRetainsAdmin(orgId); // a role/enabled change may strip the last admin
    }

    /**
     * A partial update. {@code UpdateUserRequest.roles} carries no {@code @NotNull}, so this reaches the
     * removal guard as a null set — and without the null branch every held role fails {@code null.contains(…)}
     * with an NPE, i.e. a 500 on the ordinary "rename the user" request.
     */
    @Test
    void anUpdateThatDoesNotTouchTheRoleSetAsksTheCeilingNothing() {
        UUID id = UUID.randomUUID();
        UserAccount updated = user(id);
        when(userService.updateUser(eq(id), any())).thenReturn(updated);

        service.updateUser(id, new UserUpdate("New Name", "e@example.com", true, null));

        verifyNoInteractions(membershipDenies);
        verify(userService, never()).findById(any()); // nor does it pay to hydrate the pre-state
    }

    /** And the delta itself: only the roles the replace DROPS are put to the lift ceiling. */
    @Test
    void aReplaceThatOmitsAHeldRoleAsksTheCeilingAboutThatRoleOnly() {
        UUID id = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();
        // Built before the stubbing below: a stub started inside an unfinished when(...) reads to Mockito as
        // the outer one going wrong.
        Set<RoleRef> held = Set.of(role(kept, "ROLE_KEPT"), role(dropped, "ROLE_GONE"));
        UserAccount current = user(id);
        UserAccount after = user(id);
        when(current.getRoles()).thenAnswer(invocation -> held);
        when(userService.findById(id)).thenReturn(Optional.of(current));
        when(userService.updateUser(eq(id), any())).thenReturn(after);

        service.updateUser(id, new UserUpdate("N", "e@example.com", true, Set.of("ROLE_KEPT")));

        // Asked as ONE set, so the actor is resolved once rather than once per dropped role.
        verify(membershipDenies).requireMayDropRoles(List.of(dropped));
    }

    private RoleRef role(UUID id, String name) {
        RoleRef ref = mock(RoleRef.class);
        when(ref.getId()).thenReturn(id);
        when(ref.getName()).thenReturn(name);
        return ref;
    }

    @Test
    void disablingAUserRecountsThatUsersTierAfterTheChange() {
        UUID id = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UserAccount updated = user(id);
        when(updated.getOrgId()).thenReturn(orgId);
        when(userService.setEnabled(id, false)).thenReturn(updated);

        service.setEnabled(id, false);

        verify(lastAdminGuard).ensureTierRetainsAdmin(orgId);
    }

    @Test
    void settingUserPermissionsRecountsThatUsersTierAfterTheChange() {
        UUID id = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UserAccount updated = user(id);
        when(updated.getOrgId()).thenReturn(orgId);
        when(userService.setDirectPermissions(eq(id), any())).thenReturn(updated);

        service.setUserPermissions(id, Set.of("user:read"));

        verify(lastAdminGuard).ensureTierRetainsAdmin(orgId); // a direct-perm edit can strip a denied admin's user:update
    }

    private UserAccount user(UUID id) {
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.getRoles()).thenReturn(Set.of());
        when(account.getDirectPermissionNames()).thenReturn(Set.of());
        return account;
    }
}
