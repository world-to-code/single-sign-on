package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicySpec;
import com.example.sso.authpolicy.AuthPolicyUpdate;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.authpolicy.internal.domain.AuthPolicy;
import com.example.sso.authpolicy.internal.domain.AuthPolicyRepository;
import com.example.sso.authpolicy.internal.domain.AuthPolicyRoleRepository;
import com.example.sso.authpolicy.internal.domain.AuthPolicyStep;
import com.example.sso.authpolicy.internal.domain.AuthPolicyStepFactor;
import com.example.sso.authpolicy.internal.domain.AuthPolicyStepFactorRepository;
import com.example.sso.authpolicy.internal.domain.AuthPolicyStepRepository;
import com.example.sso.authpolicy.internal.domain.AuthPolicyUserRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthPolicyAdminServiceImpl}: duplicate-name and empty-step validation on create,
 * the "Default fallback policy is immutable" guard on update/delete, and the not-found paths. Steps,
 * factors and assignments are now persisted EXPLICITLY, so create/delete are asserted with
 * {@code verify(...)} against the step/factor/assignment repositories (no JPA cascade).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthPolicyAdminServiceImplTest {

    @Mock private AuthPolicyRepository repository;
    @Mock private OrgContext orgContext;
    @Mock private AuthPolicyStepRepository stepRepository;
    @Mock private AuthPolicyStepFactorRepository stepFactorRepository;
    @Mock private AuthPolicyUserRepository userRepository;
    @Mock private AuthPolicyRoleRepository roleRepository;

    private AuthPolicyAdminServiceImpl service;

    @BeforeEach
    void platformContext() {
        // Default to the platform (no org bound) → the global tier, matching org_id-null test policies.
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty());
        // Exercise the REAL tier guard (driven by the mocked OrgContext) so the isolation checks are genuine.
        service = new AuthPolicyAdminServiceImpl(
                repository, new OrgTierGuard(orgContext),
                stepRepository, stepFactorRepository, userRepository, roleRepository);
    }

    private AuthPolicySpec spec(String name, List<Set<AuthFactor>> steps) {
        return new AuthPolicySpec(name, 10, true, true, true, steps, Set.of(), Set.of(), 15);
    }

    private AuthPolicyUpdate update(List<Set<AuthFactor>> steps) {
        return new AuthPolicyUpdate(20, true, true, true, steps, Set.of(), Set.of(), 30);
    }

    @Test
    void createRejectsADuplicateName() {
        when(repository.findByNameAndOrgIdIsNull("MFA")).thenReturn(Optional.of(new AuthPolicy("MFA", 1)));

        assertThatThrownBy(() -> service.create(spec("MFA", List.of(Set.of(AuthFactor.PASSWORD)))))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsAnEmptyStep() {
        when(repository.findByNameAndOrgIdIsNull("MFA")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(spec("MFA", List.of(Set.of()))))
                .isInstanceOf(BadRequestException.class);
        verify(stepRepository, never()).save(any());
    }

    @Test
    void createPersistsThePolicyWithEachStepAndFactorExplicitly() {
        when(repository.findByNameAndOrgIdIsNull("MFA")).thenReturn(Optional.empty());
        when(repository.save(any(AuthPolicy.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByPolicyId(any())).thenReturn(List.of());
        when(stepRepository.save(any(AuthPolicyStep.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthPolicyView view = service.create(
                spec("MFA", List.of(Set.of(AuthFactor.PASSWORD), Set.of(AuthFactor.TOTP))));

        assertThat(view.getName()).isEqualTo("MFA");
        verify(repository).save(any(AuthPolicy.class));
        verify(stepRepository, times(2)).save(any(AuthPolicyStep.class));           // two ordered steps
        verify(stepFactorRepository, times(2)).save(any(AuthPolicyStepFactor.class)); // one factor each
    }

    @Test
    void updatingTheDefaultFallbackPolicyIsRejected() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(new AuthPolicy(AuthPolicyResolver.DEFAULT_NAME, 0)));

        assertThatThrownBy(() -> service.update(id, update(List.of(Set.of(AuthFactor.PASSWORD)))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void updatingAMissingPolicyThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, update(List.of(Set.of(AuthFactor.PASSWORD)))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deletingTheDefaultPolicyIsRejected() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(new AuthPolicy(AuthPolicyResolver.DEFAULT_NAME, 0)));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(BadRequestException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void deletingAMissingPolicyThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteExplicitlyRemovesStepsAssignmentsThenThePolicy() {
        UUID id = UUID.randomUUID();
        AuthPolicy policy = new AuthPolicy("MFA", 5);
        when(repository.findById(id)).thenReturn(Optional.of(policy));
        when(stepRepository.findByPolicyId(id)).thenReturn(List.of());

        service.delete(id);

        verify(stepRepository).findByPolicyId(id);
        verify(userRepository).deleteByPolicyId(id);
        verify(roleRepository).deleteByPolicyId(id);
        verify(repository).delete(policy);
    }

    // --- adversarial: tenant/platform tier isolation (privilege escalation & cross-tenant access) --------

    @Test
    void createStampsThePolicyWithTheBoundTenantAsOwner() {
        UUID orgA = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findByNameAndOrgId("MFA", orgA)).thenReturn(Optional.empty());
        when(repository.save(any(AuthPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(spec("MFA", List.of(Set.of(AuthFactor.PASSWORD))));

        ArgumentCaptor<AuthPolicy> saved = ArgumentCaptor.forClass(AuthPolicy.class);
        verify(repository).save(saved.capture());
        // A tenant admin can only ever create in their OWN org — never a global (org_id null) policy.
        assertThat(saved.getValue().getOrgId()).isEqualTo(orgA);
    }

    @Test
    void createInATenantChecksDuplicatesOnlyWithinThatTenantsTier() {
        UUID orgA = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        // A GLOBAL policy of the same name must NOT block a tenant creating its own — only the org tier counts.
        when(repository.findByNameAndOrgId("MFA", orgA)).thenReturn(Optional.empty());
        when(repository.save(any(AuthPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(spec("MFA", List.of(Set.of(AuthFactor.PASSWORD))));

        verify(repository).save(any(AuthPolicy.class));
        verify(repository, never()).findByNameAndOrgIdIsNull(any()); // never consulted the global tier
    }

    @Test
    void createRejectsADuplicateWithinTheSameTenant() {
        UUID orgA = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findByNameAndOrgId("MFA", orgA)).thenReturn(Optional.of(orgScoped("MFA", orgA)));

        assertThatThrownBy(() -> service.create(spec("MFA", List.of(Set.of(AuthFactor.PASSWORD)))))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void aTenantAdminCannotUpdateAGlobalPolicy() {
        UUID orgA = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findById(id)).thenReturn(Optional.of(new AuthPolicy("Global-MFA", 9))); // org_id null

        // NotFound (not Forbidden) so a tenant cannot even learn a global policy exists — no enumeration.
        assertThatThrownBy(() -> service.update(id, update(List.of(Set.of(AuthFactor.PASSWORD)))))
                .isInstanceOf(NotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void aTenantAdminCannotDeleteAGlobalPolicy() {
        UUID orgA = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findById(id)).thenReturn(Optional.of(new AuthPolicy("Global-MFA", 9)));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void aTenantAdminCannotUpdateAnotherTenantsPolicy() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findById(id)).thenReturn(Optional.of(orgScoped("B-MFA", orgB)));

        assertThatThrownBy(() -> service.update(id, update(List.of(Set.of(AuthFactor.PASSWORD)))))
                .isInstanceOf(NotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void aTenantAdminCannotDeleteAnotherTenantsPolicy() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findById(id)).thenReturn(Optional.of(orgScoped("B-MFA", orgB)));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void aTenantAdminTouchingTheGlobalDefaultGetsNotFoundBeforeLearningItIsTheDefault() {
        UUID orgA = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findById(id)).thenReturn(Optional.of(new AuthPolicy(AuthPolicyResolver.DEFAULT_NAME, 0)));

        // The tier guard fires first → NotFound, not the "Default is immutable" BadRequest (no info leak).
        assertThatThrownBy(() -> service.update(id, update(List.of(Set.of(AuthFactor.PASSWORD)))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void thePlatformAdminCannotMutateAnOrgPolicyWithoutDrillingIn() {
        UUID orgB = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        // Platform context (no org bound) — default from @BeforeEach — must not reach into a tenant's policy.
        when(repository.findById(id)).thenReturn(Optional.of(orgScoped("B-MFA", orgB)));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void anOrgScopedPolicyCoincidentallyNamedDefaultIsStillEditableByItsOwner() {
        UUID orgA = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        AuthPolicy orgDefault = orgScoped(AuthPolicyResolver.DEFAULT_NAME, orgA); // org-owned, not the global fallback
        when(repository.findById(id)).thenReturn(Optional.of(orgDefault));
        when(repository.save(any(AuthPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(id, update(List.of(Set.of(AuthFactor.PASSWORD))));

        verify(repository).save(orgDefault); // the immutable guard applies only to the GLOBAL Default
    }

    @Test
    void anOrgScopedPolicyNamedDefaultIsStillDeletableByItsOwner() {
        UUID orgA = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        AuthPolicy orgDefault = orgScoped(AuthPolicyResolver.DEFAULT_NAME, orgA);
        when(repository.findById(id)).thenReturn(Optional.of(orgDefault));

        service.delete(id);

        verify(repository).delete(orgDefault);
    }

    private AuthPolicy orgScoped(String name, UUID orgId) {
        return new AuthPolicy(name, 5, orgId);
    }
}
