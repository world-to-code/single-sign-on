package com.example.sso.session.internal.application;

import com.example.sso.session.IpRuleSpec;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.session.SessionPolicySpec;
import com.example.sso.session.SessionPolicyUpdate;
import com.example.sso.session.internal.domain.SessionPolicy;
import com.example.sso.session.internal.domain.SessionPolicyRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.RoleRef;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link SessionPolicyServiceImpl}: resolution off the in-memory cache and the admin CRUD
 * guards. Resolution is pure logic over cached entities, so those tests assert on the RETURNED policy
 * (highest applicable priority wins; disabled ones are skipped; a global/empty-assignment policy applies
 * to all; an unknown user or no match falls back to Default). CRUD tests assert on the thrown
 * {@link com.example.sso.shared.error.ApiException} subtypes and {@code verify()} persistence + cache
 * refresh where that IS the unit's job. Real {@link SessionPolicy} entities back the cache (its methods
 * implement {@link SessionPolicyDetails}); collaborators are mocked.
 */
@ExtendWith(MockitoExtension.class)
class SessionPolicyServiceImplTest {

    @Mock
    private SessionPolicyRepository repository;
    @Mock
    private UserService users;

    @InjectMocks
    private SessionPolicyServiceImpl service;

    private void cache(SessionPolicy... policies) {
        when(repository.findAllWithAssignmentsByPriorityDesc()).thenReturn(List.of(policies));
        service.load(); // @PostConstruct populates the volatile cache in a real run
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
        SessionPolicy def = new SessionPolicy(SessionPolicyService.DEFAULT_NAME, 0); // global
        SessionPolicy high = new SessionPolicy("High", 10);
        high.assignUsers(Set.of(userId));
        cache(high, def);

        SessionPolicyDetails resolved = service.resolveForUser(userWith(userId));

        assertThat(resolved.getName()).isEqualTo("High");
    }

    @Test
    void resolveForUserMatchesByRoleAssignment() {
        UUID roleId = UUID.randomUUID();
        RoleRef role = mock(RoleRef.class);
        when(role.getId()).thenReturn(roleId);
        SessionPolicy def = new SessionPolicy(SessionPolicyService.DEFAULT_NAME, 0);
        SessionPolicy byRole = new SessionPolicy("ByRole", 7);
        byRole.assignRoles(Set.of(roleId));
        cache(byRole, def);

        SessionPolicyDetails resolved = service.resolveForUser(userWith(UUID.randomUUID(), role));

        assertThat(resolved.getName()).isEqualTo("ByRole");
    }

    @Test
    void resolveForUserSkipsDisabledPoliciesAndFallsBackToDefault() {
        UUID userId = UUID.randomUUID();
        SessionPolicy def = new SessionPolicy(SessionPolicyService.DEFAULT_NAME, 0);
        SessionPolicy high = new SessionPolicy("High", 10);
        high.assignUsers(Set.of(userId));
        high.disable();
        cache(high, def);

        SessionPolicyDetails resolved = service.resolveForUser(userWith(userId));

        assertThat(resolved.getName()).isEqualTo(SessionPolicyService.DEFAULT_NAME);
    }

    @Test
    void aGlobalUnassignedPolicyAppliesToEveryUser() {
        SessionPolicy def = new SessionPolicy(SessionPolicyService.DEFAULT_NAME, 0);
        SessionPolicy global = new SessionPolicy("GlobalStrict", 5); // no assignments → applies to all
        cache(global, def);

        SessionPolicyDetails resolved = service.resolveForUser(userWith(UUID.randomUUID()));

        assertThat(resolved.getName()).isEqualTo("GlobalStrict");
    }

    @Test
    void resolveForUsernameFallsBackToDefaultWhenUserIsUnknown() {
        SessionPolicy def = new SessionPolicy(SessionPolicyService.DEFAULT_NAME, 0);
        cache(def);
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(service.resolveForUsername("ghost").getName()).isEqualTo(SessionPolicyService.DEFAULT_NAME);
    }

    @Test
    void defaultPolicyThrowsWhenTheDefaultIsMissingFromTheCache() {
        cache(); // empty cache → the invariant is violated

        assertThatThrownBy(() -> service.defaultPolicy()).isInstanceOf(IllegalStateException.class);
    }

    // --- create ---

    @Test
    void createRejectsADuplicateName() {
        when(repository.findByName("Dup")).thenReturn(Optional.of(new SessionPolicy("Dup", 1)));

        assertThatThrownBy(() -> service.create(spec("Dup", "TOTP")))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsEmptyReauthFactors() {
        when(repository.findByName("Empty")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(spec("Empty", "  ")))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsAnUnknownReauthFactor() {
        when(repository.findByName("Bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(spec("Bad", "TOTP,SMS")))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsAnUnknownStepUpFactor() {
        // The stronger sensitive-action factors are validated INDEPENDENTLY of the general re-auth factors,
        // so a valid reauthFactors cannot smuggle an unknown stepUpFactors through.
        when(repository.findByName("BadStepUp")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(spec("BadStepUp", "TOTP", "FIDO2,SMS")))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createPersistsAndRefreshesOnValidInput() {
        when(repository.findByName("Strict")).thenReturn(Optional.empty());
        when(repository.save(any(SessionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findAllWithAssignmentsByPriorityDesc()).thenReturn(List.of());

        SessionPolicyDetails created = service.create(spec("Strict", "TOTP,FIDO2", "FIDO2"));

        assertThat(created.getName()).isEqualTo("Strict");
        assertThat(created.getReauthFactors()).isEqualTo("TOTP,FIDO2");
        assertThat(created.getStepUpFactors()).isEqualTo("FIDO2");             // stronger sensitive set
        assertThat(created.getSensitiveReauthWindowMinutes()).isEqualTo(2);    // window round-trips
        verify(repository).save(any(SessionPolicy.class));
        verify(repository).findAllWithAssignmentsByPriorityDesc(); // cache refreshed
    }

    @Test
    void createRejectsAnInvalidCidr() {
        when(repository.findByName("BadCidr")).thenReturn(Optional.empty());
        SessionPolicySpec spec = new SessionPolicySpec("BadCidr", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", Set.of(), Set.of(), List.of(new IpRuleSpec("not-a-cidr", "BLOCK", 0)));

        assertThatThrownBy(() -> service.create(spec)).isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createPersistsIpRulesInEvaluationOrder() {
        when(repository.findByName("Net")).thenReturn(Optional.empty());
        when(repository.save(any(SessionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findAllWithAssignmentsByPriorityDesc()).thenReturn(List.of());
        SessionPolicySpec spec = new SessionPolicySpec("Net", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", Set.of(), Set.of(),
                List.of(new IpRuleSpec("0.0.0.0/0", "BLOCK", 1), new IpRuleSpec("10.0.0.0/8", "ALLOW", 0)));

        SessionPolicyDetails created = service.create(spec);

        // getIpRules() returns them sorted by priority asc — ALLOW(0) before BLOCK(1) — regardless of input order.
        assertThat(created.getIpRules()).extracting(IpRuleSpec::cidr).containsExactly("10.0.0.0/8", "0.0.0.0/0");
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
        when(repository.findAllWithAssignmentsByPriorityDesc()).thenReturn(List.of());

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
    void deleteRemovesAndRefreshes() {
        UUID id = UUID.randomUUID();
        SessionPolicy custom = new SessionPolicy("Custom", 3);
        when(repository.findById(id)).thenReturn(Optional.of(custom));
        when(repository.findAllWithAssignmentsByPriorityDesc()).thenReturn(List.of());

        service.delete(id);

        verify(repository).delete(custom);
        verify(repository).findAllWithAssignmentsByPriorityDesc();
    }
}
