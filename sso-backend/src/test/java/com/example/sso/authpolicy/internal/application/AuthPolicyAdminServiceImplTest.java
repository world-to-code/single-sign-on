package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicySpec;
import com.example.sso.authpolicy.AuthPolicyUpdate;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.authpolicy.internal.domain.AuthPolicy;
import com.example.sso.authpolicy.internal.domain.AuthPolicyRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthPolicyAdminServiceImpl}: duplicate-name and empty-step validation on
 * create, the "Default fallback policy is immutable" guard on update/delete, and the not-found paths.
 * Persistence is the unit's job, so create/delete are asserted with {@code verify(...)}.
 */
@ExtendWith(MockitoExtension.class)
class AuthPolicyAdminServiceImplTest {

    @Mock private AuthPolicyRepository repository;

    @InjectMocks private AuthPolicyAdminServiceImpl service;

    private AuthPolicySpec spec(String name, List<Set<AuthFactor>> steps) {
        return new AuthPolicySpec(name, 10, true, true, true, steps, Set.of(), Set.of(), 15);
    }

    private AuthPolicyUpdate update(List<Set<AuthFactor>> steps) {
        return new AuthPolicyUpdate(20, true, true, true, steps, Set.of(), Set.of(), 30);
    }

    @Test
    void createRejectsADuplicateName() {
        when(repository.findByName("MFA")).thenReturn(Optional.of(new AuthPolicy("MFA", 1)));

        assertThatThrownBy(() -> service.create(spec("MFA", List.of(Set.of(AuthFactor.PASSWORD)))))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsAnEmptyStep() {
        when(repository.findByName("MFA")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(spec("MFA", List.of(Set.of()))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void createPersistsThePolicyWithOrderedSteps() {
        when(repository.findByName("MFA")).thenReturn(Optional.empty());
        when(repository.save(any(AuthPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthPolicyView view = service.create(
                spec("MFA", List.of(Set.of(AuthFactor.PASSWORD), Set.of(AuthFactor.TOTP))));

        assertThat(view.getName()).isEqualTo("MFA");
        assertThat(view.getSteps()).hasSize(2);
        verify(repository).save(any(AuthPolicy.class));
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
    void deleteRemovesANormalPolicy() {
        UUID id = UUID.randomUUID();
        AuthPolicy policy = new AuthPolicy("MFA", 5);
        when(repository.findById(id)).thenReturn(Optional.of(policy));

        service.delete(id);

        verify(repository).delete(policy);
    }
}
