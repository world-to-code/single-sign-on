package com.example.sso.admin.internal.shared.application;

import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LastAdminGuard}: the actor-independent invariant that every tier keeps an enabled,
 * effective administrator. The platform tier counts enabled global {@code ROLE_ADMIN} supers; a tenant tier
 * counts enabled {@code ROLE_ORG_ADMIN} holders that still hold {@code user:update} after deny. Every path takes
 * the per-tier advisory lock first (the lock is mocked here; its real serialization is proven by the
 * concurrency integration test, which a mocked/sequential unit test cannot cover).
 */
@ExtendWith(MockitoExtension.class)
class LastAdminGuardTest {

    @Mock private RoleService roleService;
    @Mock private UserService userService;
    @Mock private TierAdvisoryLock tierLock;
    @InjectMocks private LastAdminGuard guard;

    // --- platform tier (orgId == null) ---------------------------------------------------------------

    @Test
    void platformIsANoOpWhenTheAdminRoleDoesNotExist() {
        when(roleService.findByName(Roles.ADMIN)).thenReturn(Optional.empty());

        assertThatCode(() -> guard.ensureTierRetainsAdmin(null)).doesNotThrowAnyException();
    }

    @Test
    void platformPassesWithAnEnabledSuper() {
        stubGlobalAdmins(List.of(enabled(UUID.randomUUID())));

        assertThatCode(() -> guard.ensureTierRetainsAdmin(null)).doesNotThrowAnyException();
    }

    @Test
    void platformRejectsWhenEveryGlobalSuperIsDisabled() {
        stubGlobalAdmins(List.of(member(UUID.randomUUID(), false, null)));

        assertThatThrownBy(() -> guard.ensureTierRetainsAdmin(null)).isInstanceOf(ConflictException.class);
    }

    @Test
    void platformTakesThePlatformAdvisoryLock() {
        stubGlobalAdmins(List.of(enabled(UUID.randomUUID())));

        guard.ensureTierRetainsAdmin(null);

        verify(tierLock).serialize(null);
    }

    // --- tenant tier (orgId != null) -----------------------------------------------------------------

    @Test
    void orgPassesWithAnEnabledAdminThatStillHoldsUserUpdate() {
        UUID orgId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        stubOrgAdmins(orgId, List.of(enabledIn(adminId, orgId)));
        when(userService.effectiveAuthorities(adminId)).thenReturn(Set.of(Permissions.USER_UPDATE));

        assertThatCode(() -> guard.ensureTierRetainsAdmin(orgId)).doesNotThrowAnyException();
    }

    @Test
    void orgRejectsWhenTheLastAdminsUserUpdateHasBeenDeniedAway() {
        UUID orgId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        stubOrgAdmins(orgId, List.of(enabledIn(adminId, orgId)));
        // deny-applied effective authorities no longer include the appoint/manage capability
        when(userService.effectiveAuthorities(adminId)).thenReturn(Set.of(Permissions.USER_READ));

        assertThatThrownBy(() -> guard.ensureTierRetainsAdmin(orgId)).isInstanceOf(ConflictException.class);
    }

    @Test
    void orgRejectsWhenTheOnlyOrgAdminIsDisabled() {
        UUID orgId = UUID.randomUUID();
        stubOrgAdmins(orgId, List.of(member(UUID.randomUUID(), false, orgId)));

        assertThatThrownBy(() -> guard.ensureTierRetainsAdmin(orgId)).isInstanceOf(ConflictException.class);
    }

    @Test
    void orgRejectsWhenADisabledOrgAdminStillHoldsUserUpdate() {
        // The enabled check is load-bearing: a disabled account keeps its authorities but cannot administer.
        UUID orgId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        stubOrgAdmins(orgId, List.of(member(adminId, false, orgId)));
        // lenient: correct code short-circuits on !isEnabled and never reads authorities; a mutant that drops the
        // isEnabled check WOULD read them (and wrongly pass) — this stub is what makes the reject provable.
        lenient().when(userService.effectiveAuthorities(adminId)).thenReturn(Set.of(Permissions.USER_UPDATE));

        assertThatThrownBy(() -> guard.ensureTierRetainsAdmin(orgId)).isInstanceOf(ConflictException.class);
    }

    @Test
    void orgRejectsWhenItsOnlyOrgAdminRoleHolderBelongsToAnotherTenant() {
        // Same-tier defense: a foreign-org holder of the org's own role must NOT be counted as its survivor.
        UUID orgId = UUID.randomUUID();
        UUID foreignAdmin = UUID.randomUUID();
        stubOrgAdmins(orgId, List.of(enabledIn(foreignAdmin, UUID.randomUUID())));

        assertThatThrownBy(() -> guard.ensureTierRetainsAdmin(orgId)).isInstanceOf(ConflictException.class);
    }

    @Test
    void orgDoesNotCountTheGlobalTemplatesHoldersWhenItHasNoOwnRole() {
        // findByName falls back to the global ROLE_ORG_ADMIN template (org_id null); its holders belong to no
        // tenant, so the guard must reject it outright — never even read its holders — and treat the org as
        // having no own admin role (a no-op, nothing to guard).
        UUID orgId = UUID.randomUUID();
        UUID templateRoleId = UUID.randomUUID();
        RoleRef globalTemplate = mock(RoleRef.class);
        lenient().when(globalTemplate.getId()).thenReturn(templateRoleId);
        when(globalTemplate.getOrgId()).thenReturn(null); // the global template, not the org's own role
        when(roleService.findByName(Roles.ORG_ADMIN, orgId)).thenReturn(Optional.of(globalTemplate));

        assertThatCode(() -> guard.ensureTierRetainsAdmin(orgId)).doesNotThrowAnyException();
        verify(roleService, never()).effectiveHolders(templateRoleId);
    }

    @Test
    void orgIsANoOpWhenTheOrgHasNoOrgAdminRole() {
        UUID orgId = UUID.randomUUID();
        when(roleService.findByName(Roles.ORG_ADMIN, orgId)).thenReturn(Optional.empty());

        assertThatCode(() -> guard.ensureTierRetainsAdmin(orgId)).doesNotThrowAnyException();
    }

    @Test
    void orgTakesThatOrgsAdvisoryLock() {
        UUID orgId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        stubOrgAdmins(orgId, List.of(enabledIn(adminId, orgId)));
        when(userService.effectiveAuthorities(adminId)).thenReturn(Set.of(Permissions.USER_UPDATE));

        guard.ensureTierRetainsAdmin(orgId);

        verify(tierLock).serialize(orgId);
    }

    // --- fixtures -------------------------------------------------------------------------------------

    private void stubGlobalAdmins(List<UserAccount> members) {
        RoleRef role = roleWith(members); // build the role + its holder stubs BEFORE the outer stubbing starts
        when(roleService.findByName(Roles.ADMIN)).thenReturn(Optional.of(role));
    }

    private void stubOrgAdmins(UUID orgId, List<UserAccount> members) {
        RoleRef role = roleWith(members);
        when(role.getOrgId()).thenReturn(orgId); // the org's OWN role (not the global template the guard rejects)
        when(roleService.findByName(Roles.ORG_ADMIN, orgId)).thenReturn(Optional.of(role));
    }

    private RoleRef roleWith(List<UserAccount> members) {
        UUID roleId = UUID.randomUUID();
        RoleRef role = mock(RoleRef.class);
        when(role.getId()).thenReturn(roleId);
        when(roleService.effectiveHolders(roleId)).thenReturn(members); // direct + group-delegated holders
        return role;
    }

    private UserAccount enabled(UUID id) {
        return member(id, true, null); // platform tier: a super is a global (null-org) user
    }

    private UserAccount enabledIn(UUID id, UUID orgId) {
        return member(id, true, orgId);
    }

    private UserAccount member(UUID id, boolean enabled, UUID orgId) {
        UserAccount user = mock(UserAccount.class);
        lenient().when(user.getId()).thenReturn(id);
        lenient().when(user.isEnabled()).thenReturn(enabled);
        lenient().when(user.getOrgId()).thenReturn(orgId);
        return user;
    }
}
