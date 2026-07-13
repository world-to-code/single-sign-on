package com.example.sso.session.internal.policy.application;

import com.example.sso.session.networkzone.IpRuleSpec;
import com.example.sso.session.networkzone.NetworkZoneService;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.SessionPolicySpec;
import com.example.sso.session.policy.SessionPolicyUpdate;
import com.example.sso.session.internal.policy.domain.SessionPolicy;
import com.example.sso.session.internal.policy.domain.SessionPolicyIpRuleRepository;
import com.example.sso.session.internal.policy.domain.SessionPolicyRepository;
import com.example.sso.session.internal.policy.domain.SessionPolicyRole;
import com.example.sso.session.internal.policy.domain.SessionPolicyRoleRepository;
import com.example.sso.session.internal.policy.domain.SessionPolicyUser;
import com.example.sso.session.internal.policy.domain.SessionPolicyUserRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.example.sso.tenancy.OrgTierGuard;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link SessionPolicyServiceImpl}: resolution off the in-memory cache and the admin CRUD
 * guards. Assignments now live in explicit child rows, so resolution tests seed the cache from the policy
 * list plus {@link SessionPolicyUser}/{@link SessionPolicyRole} rows and assert on the RETURNED policy
 * (highest applicable priority wins; disabled ones are skipped; a global/empty-assignment policy applies to
 * all; an unknown user or no match falls back to Default). CRUD tests assert on the thrown
 * {@link com.example.sso.shared.error.ApiException} subtypes and {@code verify()} persistence + cache refresh
 * where that IS the unit's job. Collaborators are mocked.
 */
@ExtendWith(MockitoExtension.class)
class SessionPolicyServiceImplTest {

    @Mock
    private SessionPolicyRepository repository;
    @Mock
    private SessionPolicyUserRepository policyUsers;
    @Mock
    private SessionPolicyRoleRepository policyRoles;
    @Mock
    private SessionPolicyIpRuleRepository policyIpRules;
    @Mock
    private UserService users;
    @Mock
    private NetworkZoneService networkZones;
    @Mock
    private OrgContext orgContext;
    @Mock
    private ApplicationEventPublisher events;

    private SessionPolicyServiceImpl service;

    @BeforeEach
    void setUp() {
        // Default to the platform context (no org bound → the global tier); the cache reload runs as platform.
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty());
        lenient().when(orgContext.callAsPlatform(any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        // Exercise the REAL tier guard (driven by the mocked OrgContext) so the isolation checks are genuine.
        service = new SessionPolicyServiceImpl(
                repository, policyUsers, policyRoles, policyIpRules, users, networkZones,
                orgContext, new OrgTierGuard(orgContext), events);
    }

    private SessionPolicy policy(String name, int priority) {
        SessionPolicy p = new SessionPolicy(name, priority);
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        return p;
    }

    private void loadCache(List<SessionPolicy> policies, List<SessionPolicyUser> userRows,
                           List<SessionPolicyRole> roleRows) {
        when(repository.findAllByOrderByPriorityDesc()).thenReturn(policies);
        when(policyUsers.findAll()).thenReturn(userRows);
        when(policyRoles.findAll()).thenReturn(roleRows);
        when(policyIpRules.findAll()).thenReturn(List.of());
        service.load(); // @PostConstruct populates the volatile cache (via a platform reload) in a real run
    }

    // Seed the cache from bare policies (no user/role assignments) — used by the cross-tenant resolution tests.
    private void cache(SessionPolicy... policies) {
        loadCache(List.of(policies), List.of(), List.of());
    }

    private UserAccount userWith(UUID id, RoleRef... roles) {
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(id);
        doReturn(Set.of(roles)).when(user).getRoles();
        return user;
    }

    private SessionPolicySpec spec(String name, String reauthFactors) {
        return spec(name, reauthFactors, reauthFactors);
    }

    private SessionPolicySpec spec(String name, String reauthFactors, String stepUpFactors) {
        return new SessionPolicySpec(name, 5, true, 480, 30, 15, reauthFactors, 2, stepUpFactors, false, 0, false,
                "Lax", Set.of(), Set.of(), List.of());
    }

    private SessionPolicyUpdate update(int priority, boolean enabled, String reauthFactors) {
        return new SessionPolicyUpdate(priority, enabled, 480, 30, 15, reauthFactors, 2, reauthFactors, false, 0, false,
                "Lax", Set.of(), Set.of(), List.of());
    }

    // --- resolution ---

    @Test
    void resolveForUserPicksTheHighestPriorityApplicablePolicy() {
        UUID userId = UUID.randomUUID();
        SessionPolicy def = policy(SessionPolicyService.DEFAULT_NAME, 0); // global
        SessionPolicy high = policy("High", 10);
        loadCache(List.of(high, def), List.of(new SessionPolicyUser(high.getId(), userId)), List.of());

        SessionPolicyDetails resolved = service.resolveForUser(userWith(userId));

        assertThat(resolved.getName()).isEqualTo("High");
    }

    @Test
    void resolveForUserMatchesByRoleAssignment() {
        UUID roleId = UUID.randomUUID();
        RoleRef role = mock(RoleRef.class);
        when(role.getId()).thenReturn(roleId);
        SessionPolicy def = policy(SessionPolicyService.DEFAULT_NAME, 0);
        SessionPolicy byRole = policy("ByRole", 7);
        loadCache(List.of(byRole, def), List.of(), List.of(new SessionPolicyRole(byRole.getId(), roleId)));

        SessionPolicyDetails resolved = service.resolveForUser(userWith(UUID.randomUUID(), role));

        assertThat(resolved.getName()).isEqualTo("ByRole");
    }

    @Test
    void resolveForUserSkipsDisabledPoliciesAndFallsBackToDefault() {
        UUID userId = UUID.randomUUID();
        SessionPolicy def = policy(SessionPolicyService.DEFAULT_NAME, 0);
        SessionPolicy high = policy("High", 10);
        high.disable();
        loadCache(List.of(high, def), List.of(new SessionPolicyUser(high.getId(), userId)), List.of());

        SessionPolicyDetails resolved = service.resolveForUser(userWith(userId));

        assertThat(resolved.getName()).isEqualTo(SessionPolicyService.DEFAULT_NAME);
    }

    @Test
    void aGlobalUnassignedPolicyAppliesToEveryUser() {
        SessionPolicy def = policy(SessionPolicyService.DEFAULT_NAME, 0);
        SessionPolicy global = policy("GlobalStrict", 5); // no assignments → applies to all
        loadCache(List.of(global, def), List.of(), List.of());

        SessionPolicyDetails resolved = service.resolveForUser(userWith(UUID.randomUUID()));

        assertThat(resolved.getName()).isEqualTo("GlobalStrict");
    }

    @Test
    void resolveForUsernameFallsBackToDefaultWhenUserIsUnknown() {
        SessionPolicy def = policy(SessionPolicyService.DEFAULT_NAME, 0);
        loadCache(List.of(def), List.of(), List.of());
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(service.resolveForUsername("ghost").getName()).isEqualTo(SessionPolicyService.DEFAULT_NAME);
    }

    @Test
    void defaultPolicyThrowsWhenTheDefaultIsMissingFromTheCache() {
        loadCache(List.of(), List.of(), List.of()); // empty cache → the invariant is violated

        assertThatThrownBy(() -> service.defaultPolicy()).isInstanceOf(IllegalStateException.class);
    }

    // --- create ---

    @Test
    void createRejectsADuplicateName() {
        when(repository.findByNameAndOrgIdIsNull("Dup")).thenReturn(Optional.of(new SessionPolicy("Dup", 1)));

        assertThatThrownBy(() -> service.create(spec("Dup", "TOTP")))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsEmptyReauthFactors() {
        when(repository.findByNameAndOrgIdIsNull("Empty")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(spec("Empty", "  ")))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsAnUnknownReauthFactor() {
        when(repository.findByNameAndOrgIdIsNull("Bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(spec("Bad", "TOTP,SMS")))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsAnUnknownStepUpFactor() {
        // The stronger sensitive-action factors are validated INDEPENDENTLY of the general re-auth factors,
        // so a valid reauthFactors cannot smuggle an unknown stepUpFactors through.
        when(repository.findByNameAndOrgIdIsNull("BadStepUp")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(spec("BadStepUp", "TOTP", "FIDO2,SMS")))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createPersistsAndRefreshesOnValidInput() {
        when(repository.findByNameAndOrgIdIsNull("Strict")).thenReturn(Optional.empty());
        when(repository.save(any(SessionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionPolicyDetails created = service.create(spec("Strict", "TOTP,FIDO2", "FIDO2"));

        assertThat(created.getName()).isEqualTo("Strict");
        assertThat(created.getReauthFactors()).isEqualTo("TOTP,FIDO2");
        assertThat(created.getStepUpFactors()).isEqualTo("FIDO2");             // stronger sensitive set
        assertThat(created.getSensitiveReauthWindowMinutes()).isEqualTo(2);    // window round-trips
        verify(repository).save(any(SessionPolicy.class));
        verify(events).publishEvent(any(SessionPolicyCacheChanged.class)); // cache rebuilt AFTER_COMMIT
    }

    @Test
    void createRejectsAnUnknownZoneReference() {
        when(repository.findByNameAndOrgIdIsNull("BadZone")).thenReturn(Optional.empty());
        String ghost = UUID.randomUUID().toString();
        when(networkZones.exists(UUID.fromString(ghost))).thenReturn(false);
        SessionPolicySpec spec = new SessionPolicySpec("BadZone", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", Set.of(), Set.of(), List.of(new IpRuleSpec(ghost, "BLOCK", 0)));

        assertThatThrownBy(() -> service.create(spec)).isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsAMalformedZoneId() {
        when(repository.findByNameAndOrgIdIsNull("BadId")).thenReturn(Optional.empty());
        SessionPolicySpec spec = new SessionPolicySpec("BadId", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", Set.of(), Set.of(), List.of(new IpRuleSpec("not-a-uuid", "BLOCK", 0)));

        assertThatThrownBy(() -> service.create(spec)).isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createPersistsIpRulesInEvaluationOrder() {
        when(repository.findByNameAndOrgIdIsNull("Net")).thenReturn(Optional.empty());
        when(repository.save(any(SessionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));
        String office = UUID.randomUUID().toString();
        String everywhere = UUID.randomUUID().toString();
        when(networkZones.exists(any(UUID.class))).thenReturn(true);
        SessionPolicySpec spec = new SessionPolicySpec("Net", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", Set.of(), Set.of(),
                List.of(new IpRuleSpec(everywhere, "BLOCK", 1), new IpRuleSpec(office, "ALLOW", 0)));

        SessionPolicyDetails created = service.create(spec);

        // getIpRules() returns them sorted by priority asc — the priority-0 rule (office) before priority-1.
        assertThat(created.getIpRules()).extracting(IpRuleSpec::zoneId).containsExactly(office, everywhere);
    }

    // --- update ---

    @Test
    void updateOfMissingPolicyThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, update(3, true, "TOTP")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updatingTheDefaultKeepsItsPriorityAndAssignmentsFixed() {
        UUID id = UUID.randomUUID();
        SessionPolicy def = new SessionPolicy(SessionPolicyService.DEFAULT_NAME, 0);
        when(repository.findById(id)).thenReturn(Optional.of(def));
        when(repository.save(any(SessionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionPolicyDetails saved = service.update(id, update(99, false, "TOTP"));

        // Default is non-reprioritisable/non-disablable: priority stays 0 and it remains enabled,
        // but the baseline timeouts/factors it carries are still editable.
        assertThat(saved.getPriority()).isEqualTo(0);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getReauthFactors()).isEqualTo("TOTP");
    }

    // --- delete ---

    @Test
    void deletingTheDefaultIsRejected() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(new SessionPolicy(SessionPolicyService.DEFAULT_NAME, 0)));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(BadRequestException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteOfMissingPolicyThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRemovesChildRowsAndThenThePolicyAndRefreshes() {
        UUID id = UUID.randomUUID();
        SessionPolicy custom = policy("Custom", 3);
        when(repository.findById(id)).thenReturn(Optional.of(custom));

        service.delete(id);

        verify(policyUsers).deleteByPolicyId(id);
        verify(policyRoles).deleteByPolicyId(id);
        verify(policyIpRules).deleteByPolicyId(id);
        verify(repository).delete(custom);
        verify(events).publishEvent(any(SessionPolicyCacheChanged.class));
    }

    // --- adversarial: cross-tenant resolution + tier isolation --------------------------------------

    @Test
    void resolveIgnoresAnotherTenantsPolicyEvenWhenHigherPriority() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        SessionPolicy def = new SessionPolicy(SessionPolicyService.DEFAULT_NAME, 0);        // global
        SessionPolicy foreign = new SessionPolicy("B-Strict", 99, orgB);                    // another tenant's
        cache(foreign, def);
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA)); // request bound to org A

        // org B's policy sits in the shared (platform-loaded) cache but must NEVER apply to an org A
        // session — even at priority 99 it is filtered out, so resolution falls back to the global Default.
        assertThat(service.resolveForUser(userWith(UUID.randomUUID())).getName())
                .isEqualTo(SessionPolicyService.DEFAULT_NAME);
    }

    @Test
    void resolveAppliesTheBoundOrgsOwnOrgWidePolicy() {
        UUID orgA = UUID.randomUUID();
        SessionPolicy def = new SessionPolicy(SessionPolicyService.DEFAULT_NAME, 0);
        SessionPolicy orgWide = new SessionPolicy("A-Wide", 5, orgA); // org A, unassigned → org-wide
        cache(orgWide, def);
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));

        assertThat(service.resolveForUser(userWith(UUID.randomUUID())).getName()).isEqualTo("A-Wide");
    }

    @Test
    void createStampsThePolicyWithTheBoundTenantAsOwner() {
        UUID orgA = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findByNameAndOrgId("T", orgA)).thenReturn(Optional.empty());
        when(repository.save(any(SessionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(spec("T", "TOTP"));

        ArgumentCaptor<SessionPolicy> saved = ArgumentCaptor.forClass(SessionPolicy.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrgId()).isEqualTo(orgA); // never a global (org_id null) policy
    }

    @Test
    void aTenantAdminCannotUpdateAGlobalPolicy() {
        UUID orgA = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findById(id)).thenReturn(Optional.of(new SessionPolicy("Global", 5))); // org_id null

        assertThatThrownBy(() -> service.update(id, update(3, true, "TOTP")))
                .isInstanceOf(NotFoundException.class); // 404, not 403 — no enumeration of global policies
        verify(repository, never()).save(any());
    }

    @Test
    void aTenantAdminCannotDeleteAnotherTenantsPolicy() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findById(id)).thenReturn(Optional.of(new SessionPolicy("B", 5, orgB)));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void thePlatformAdminCannotMutateAnOrgPolicyWithoutDrillingIn() {
        UUID orgB = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        // Platform context (no org bound) — the @BeforeEach default — must not reach into a tenant's policy.
        when(repository.findById(id)).thenReturn(Optional.of(new SessionPolicy("B", 5, orgB)));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void aTenantsDefaultIsTheLockedFallbackWithAFrozenPriority() {
        UUID orgA = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        SessionPolicy orgDefault = new SessionPolicy(SessionPolicyService.DEFAULT_NAME, 3, orgA);
        when(repository.findById(id)).thenReturn(Optional.of(orgDefault));
        when(repository.save(any(SessionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionPolicyDetails saved = service.update(id, update(7, true, "TOTP"));

        // A tenant's per-org "Default" is the unconditional fallback, not a normal custom policy: its priority
        // is FROZEN (the requested 7 is ignored — it stays the lowest-priority catch-all), so an admin can
        // never re-rank or re-target it and strand users with no policy.
        assertThat(saved.getPriority()).isEqualTo(3);
    }

    @Test
    void aTenantsDefaultCannotBeDeleted() {
        UUID orgA = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findById(id))
                .thenReturn(Optional.of(new SessionPolicy(SessionPolicyService.DEFAULT_NAME, 1, orgA)));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(BadRequestException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void aNonGlobalDefaultPolicyStoresTheGlobalCookieSameSite() {
        // The SESSION cookie is written before the user (and thus their policy) is known, so the serializer
        // reads the GLOBAL Default's SameSite. Any other policy stores that same value — a divergent one would
        // be inert (edited, never applied), so it must never be persisted as if it meant something.
        loadCache(List.of(policy(SessionPolicyService.DEFAULT_NAME, 0)), List.of(), List.of()); // global: Lax
        when(repository.save(any(SessionPolicy.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SessionPolicySpec spec = new SessionPolicySpec("Strict-Cookies", 10, true, 480, 30, 5, "TOTP", 2,
                "TOTP", false, 0, false, "Strict", Set.of(), Set.of(), List.of());

        assertThat(service.create(spec).getCookieSameSite()).isEqualTo("Lax");
    }
}
