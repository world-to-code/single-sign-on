package com.example.sso.session.internal.policy.application;

import com.example.sso.session.networkzone.IpRuleSpec;
import com.example.sso.session.networkzone.NetworkZoneService;
import com.example.sso.session.policy.SessionBindings;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.SessionPolicySpec;
import com.example.sso.session.policy.SessionPolicyUpdate;
import com.example.sso.session.internal.policy.domain.SessionPolicy;
import com.example.sso.session.internal.policy.domain.SessionPolicyIpRuleRepository;
import com.example.sso.session.internal.policy.domain.SessionPolicyRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.role.RoleService;
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
import com.example.sso.metadata.AttributePredicate;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link SessionPolicyServiceImpl}: admin CRUD guards and the seeding invariants. Which users/roles
 * a policy governs now lives in the {@code policy_binding} matrix (written via {@link SessionBindings}), so
 * create/update/delete are asserted with {@code verify()} against the session-bindings collaborator and the
 * cross-tenant assignability guard; the resolution semantics themselves are covered by {@code UserSessionPolicyImplTest}
 * and {@code PolicyBindingResolverIT}. Collaborators are mocked; the cache still serves defaultPolicy/scalars.
 */
@ExtendWith(MockitoExtension.class)
class SessionPolicyServiceImplTest {

    @Mock
    private SessionPolicyRepository repository;
    @Mock
    private SessionPolicyIpRuleRepository policyIpRules;
    @Mock
    private SessionBindings sessionBindings;
    @Mock
    private UserService users;
    @Mock
    private RoleService roles;
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
                repository, policyIpRules, sessionBindings, users, roles, networkZones,
                orgContext, new OrgTierGuard(orgContext), events);
    }

    private SessionPolicy policy(String name, int priority) {
        SessionPolicy p = new SessionPolicy(name, priority);
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        return p;
    }

    private SessionPolicy orgPolicy(String name, int priority, UUID orgId) {
        SessionPolicy p = new SessionPolicy(name, priority, orgId);
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        return p;
    }

    private void loadCache(List<SessionPolicy> policies) {
        when(repository.findAllByOrderByPriorityDesc()).thenReturn(policies);
        when(policyIpRules.findAll()).thenReturn(List.of());
        service.load(); // @PostConstruct populates the volatile cache (via a platform reload) in a real run
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

    @Test
    void defaultPolicyThrowsWhenTheDefaultIsMissingFromTheCache() {
        loadCache(List.of()); // empty cache → the invariant is violated

        assertThatThrownBy(() -> service.defaultPolicy()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolveDefaultPrefersTheActingOrgsOwnDefaultOverTheGlobal() {
        UUID orgA = UUID.randomUUID();
        loadCache(List.of(orgPolicy(SessionPolicyService.DEFAULT_NAME, 1, orgA),
                policy(SessionPolicyService.DEFAULT_NAME, 0))); // org A's Default (1) + the global (0)
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));

        // The bound org keeps its OWN (possibly hardened) Default, not the global baseline.
        assertThat(service.resolveDefault().getPriority()).isEqualTo(1);
    }

    @Test
    void resolveDefaultFallsBackToTheGlobalWhenTheActingOrgHasNoOwnDefault() {
        UUID orgB = UUID.randomUUID();
        loadCache(List.of(policy(SessionPolicyService.DEFAULT_NAME, 0))); // only the global Default
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgB));

        assertThat(service.resolveDefault().getPriority()).isEqualTo(0);
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
    void createRejectsADuplicatePriorityInTheSameTier() {
        // Priority is unique per tier so the same-specificity tie-break is deterministic — a create at a priority
        // another policy in the tier already holds is refused.
        when(repository.findByNameAndOrgIdIsNull("DupPrio")).thenReturn(Optional.empty());
        when(repository.findByPriorityAndOrgIdIsNull(5)).thenReturn(List.of(policy("Other", 5)));

        assertThatThrownBy(() -> service.create(spec("DupPrio", "TOTP")))
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
    void createWritesTheAssignmentScopeForSameOrgSubjectsToTheMatrix() {
        UUID orgA = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findByNameAndOrgId("Scoped", orgA)).thenReturn(Optional.empty());
        when(repository.save(any(SessionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.orgIdOf(userId)).thenReturn(Optional.of(orgA));
        when(roles.orgIdOf(roleId)).thenReturn(Optional.of(orgA));
        SessionPolicySpec spec = new SessionPolicySpec("Scoped", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", Set.of(userId), Set.of(roleId), List.of());

        service.create(spec);

        verify(sessionBindings).replaceForPolicy(any(), anyInt(), eq(Set.of(userId)), eq(Set.of(roleId)), eq(Set.of()));
    }

    @Test
    void createPassesAttributePredicatesThroughToTheBindingScope() {
        UUID orgA = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findByNameAndOrgId("Attr", orgA)).thenReturn(Optional.empty());
        when(repository.save(any(SessionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));
        AttributePredicate eng = AttributePredicate.equals("dept", "eng");
        SessionPolicySpec spec = new SessionPolicySpec("Attr", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", Set.of(), Set.of(), List.of(), Set.of(eng));

        service.create(spec);

        // A predicate carries no cross-org subject, so it must NOT be routed through requireAssignable, and it
        // must reach the binding writer as the attribute set (not silently dropped or turned into all-subjects).
        verify(sessionBindings).replaceForPolicy(any(), anyInt(), eq(Set.of()), eq(Set.of()), eq(Set.of(eng)));
    }

    @Test
    void createRejectsASubjectFromAnotherOrg() {
        UUID userB = UUID.randomUUID();
        when(repository.findByNameAndOrgIdIsNull("Foreign")).thenReturn(Optional.empty());
        when(repository.save(any(SessionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.orgIdOf(userB)).thenReturn(Optional.of(UUID.randomUUID())); // a different tenant
        SessionPolicySpec spec = new SessionPolicySpec("Foreign", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", Set.of(userB), Set.of(), List.of());

        assertThatThrownBy(() -> service.create(spec)).isInstanceOf(BadRequestException.class);
        verify(sessionBindings, never()).replaceForPolicy(any(), anyInt(), any(), any(), any());
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
    void updateRejectsAPriorityHeldByAnotherPolicyInTheTier() {
        UUID id = UUID.randomUUID();
        SessionPolicy editable = policy("Editable", 5); // a non-Default, global policy
        when(repository.findById(id)).thenReturn(Optional.of(editable));
        when(repository.findByPriorityAndOrgIdIsNull(7)).thenReturn(List.of(policy("Other", 7))); // taken by another

        assertThatThrownBy(() -> service.update(id, update(7, true, "TOTP")))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateAllowsAPolicyToKeepItsOwnPriority() {
        // Self is excluded from the uniqueness check — editing a policy without moving it off its priority is fine.
        UUID id = UUID.randomUUID();
        SessionPolicy editable = policy("Editable", 5);
        when(repository.findById(id)).thenReturn(Optional.of(editable));
        when(repository.findByPriorityAndOrgIdIsNull(5)).thenReturn(List.of(editable)); // only itself at priority 5
        when(repository.save(any(SessionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionPolicyDetails saved = service.update(id, update(5, true, "TOTP"));

        assertThat(saved.getPriority()).isEqualTo(5);
        verify(repository).save(editable);
    }

    @Test
    void updatingTheDefaultKeepsItsPriorityFixedAndStaysAllSubjects() {
        UUID id = UUID.randomUUID();
        SessionPolicy def = new SessionPolicy(SessionPolicyService.DEFAULT_NAME, 0);
        when(repository.findById(id)).thenReturn(Optional.of(def));
        when(repository.save(any(SessionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        SessionPolicyDetails saved = service.update(id, update(99, false, "TOTP"));

        // Default is non-reprioritisable/non-disablable: priority stays 0 and it remains enabled, but the
        // baseline knobs are editable. It stays the unconditional all-subjects catch-all (empty binding scope).
        assertThat(saved.getPriority()).isEqualTo(0);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getReauthFactors()).isEqualTo("TOTP");
        verify(sessionBindings).replaceForPolicy(eq(def.getId()), anyInt(), eq(Set.of()), eq(Set.of()), eq(Set.of()));
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
    void deleteClearsBindingsAndIpRulesThenThePolicyAndRefreshes() {
        UUID id = UUID.randomUUID();
        SessionPolicy custom = policy("Custom", 3);
        when(repository.findById(id)).thenReturn(Optional.of(custom));

        service.delete(id);

        verify(sessionBindings).clearForPolicy(id); // assignment bindings cleared before the policy (FK RESTRICT)
        verify(policyIpRules).deleteByPolicyId(id);
        verify(repository).delete(custom);
        verify(events).publishEvent(any(SessionPolicyCacheChanged.class));
    }

    // --- adversarial: tier isolation --------------------------------------------------------------

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
        loadCache(List.of(policy(SessionPolicyService.DEFAULT_NAME, 0))); // global: Lax
        when(repository.save(any(SessionPolicy.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SessionPolicySpec spec = new SessionPolicySpec("Strict-Cookies", 10, true, 480, 30, 5, "TOTP", 2,
                "TOTP", false, 0, false, "Strict", Set.of(), Set.of(), List.of());

        assertThat(service.create(spec).getCookieSameSite()).isEqualTo("Lax");
    }
}
