package com.example.sso.admin.internal.shared.application;

import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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

    // --- the deny entry point: every reached tenant recounted, but only when the deny can matter -------

    @Test
    void everyTenantTheDenyReachesIsRecounted() {
        // The reason this entry point exists: a platform-wide (null-org) deny applies in EVERY tenant, so
        // recounting only the tier it was stamped with would leave the tenants it reaches unchecked.
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        stubHealthyOrg(orgA);
        stubHealthyOrg(orgB);

        guard.ensureDenyRetainsAdmins(Permissions.USER_UPDATE, List.of(orgA, orgB));

        verify(tierLock).serialize(orgA);
        verify(tierLock).serialize(orgB);
    }

    @Test
    void oneBrickedTenantRejectsTheWholeDeny() {
        // Tiers are locked ascending, so the healthy LOW tier is recounted before the bricked HIGH one throws.
        stubHealthyOrg(LOW_TIER);
        UUID strippedAdmin = UUID.randomUUID();
        stubOrgAdmins(HIGH_TIER, List.of(enabledIn(strippedAdmin, HIGH_TIER)));
        when(userService.effectiveAuthorities(strippedAdmin)).thenReturn(Set.of(Permissions.USER_READ));

        assertThatThrownBy(() -> guard.ensureDenyRetainsAdmins(Permissions.USER_UPDATE,
                List.of(LOW_TIER, HIGH_TIER))).isInstanceOf(ConflictException.class);
    }

    @Test
    void tierLocksAreTakenInAscendingOrderRegardlessOfInputOrder() {
        // Deadlock freedom: two concurrent multi-tier writes must request the locks in the SAME order, or each
        // can hold the other's next lock. A test that passed them already-sorted would prove nothing.
        stubHealthyOrg(LOW_TIER);
        stubHealthyOrg(HIGH_TIER);

        guard.ensureDenyRetainsAdmins(Permissions.USER_UPDATE, List.of(HIGH_TIER, LOW_TIER));

        InOrder locks = inOrder(tierLock);
        locks.verify(tierLock).serialize(LOW_TIER);
        locks.verify(tierLock).serialize(HIGH_TIER);
    }

    @Test
    void thePlatformTierIsNotRecountedForADeny() {
        // A global super is deny-exempt, so no deny can change the platform count — recounting it would always
        // pass. null is a legitimate member of the reached set (a global user's tier) and must be dropped, not
        // blow up the sort.
        guard.ensureDenyRetainsAdmins(Permissions.USER_UPDATE, Arrays.asList((UUID) null));

        verify(tierLock, never()).serialize(any());
    }

    @Test
    void aDenyThatCannotSubtractTheAdminCapabilityRecountsNothing() {
        // group:read cannot flip retainsAdminCapability (which turns on user:update alone), so recounting every
        // tenant a platform-wide veto reaches would be pure cost — and would refuse writes it has no business in.
        guard.ensureDenyRetainsAdmins(Permissions.GROUP_READ, List.of(LOW_TIER, HIGH_TIER));

        verify(tierLock, never()).serialize(any());
    }

    @Test
    void aWildcardDenyCoveringTheAdminCapabilityIsRecounted() {
        // user:* subtracts user:update without naming it — matching on the literal string would miss this.
        stubHealthyOrg(LOW_TIER);

        guard.ensureDenyRetainsAdmins("user:*", List.of(LOW_TIER));

        verify(tierLock).serialize(LOW_TIER);
    }

    @Test
    void aTenantAlreadyWithoutAnEnabledAdminIsNotBlamedOnTheDeny() {
        // A freshly onboarded tenant's invited admin is disabled until acceptance. A deny cannot disable an
        // account, so that tenant was already un-administrable — refusing here would block every platform-wide
        // veto for as long as any one tenant sits in that entirely normal state.
        stubOrgAdmins(LOW_TIER, List.of(member(UUID.randomUUID(), false, LOW_TIER)));

        assertThatCode(() -> guard.ensureDenyRetainsAdmins(Permissions.USER_UPDATE, List.of(LOW_TIER)))
                .doesNotThrowAnyException();
    }

    @Test
    void noReachedTierIsANoOp() {
        guard.ensureDenyRetainsAdmins(Permissions.USER_UPDATE, List.of());

        verify(tierLock, never()).serialize(any());
    }

    // --- fixtures -------------------------------------------------------------------------------------

    /** Two tiers with a KNOWN relative order, so lock-ordering assertions are about the guard, not luck. */
    private static final UUID LOW_TIER = new UUID(0L, 1L);
    private static final UUID HIGH_TIER = new UUID(0L, 2L);

    private void stubHealthyOrg(UUID orgId) {
        UUID adminId = UUID.randomUUID();
        stubOrgAdmins(orgId, List.of(enabledIn(adminId, orgId)));
        when(userService.effectiveAuthorities(adminId)).thenReturn(Set.of(Permissions.USER_UPDATE));
    }


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
