package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyAdminService;
import com.example.sso.authpolicy.policy.AuthPolicySpec;
import com.example.sso.authpolicy.policy.AuthPolicyStepView;
import com.example.sso.authpolicy.policy.AuthPolicyUpdate;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link PolicyAdminService} presentation adapter: each projecting method maps the
 * domain policy view to a {@link PolicyView} (factor enums to sorted names, ids to strings) and delete
 * merely delegates. Delegation is the unit's job, so {@code verify(...)} the underlying service call.
 * The underlying {@link AuthPolicyView} is mocked (its LAZY-loaded projection is covered by an IT).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PolicyAdminServiceTest {

    @Mock private AuthPolicyAdminService delegate;

    @InjectMocks private PolicyAdminService service;

    /** A view stub carrying two ordered steps and one user assignment, so PolicyView.of maps real data. */
    private AuthPolicyView policy(String name) {
        AuthPolicyView view = mock(AuthPolicyView.class);
        when(view.getId()).thenReturn(UUID.randomUUID());
        when(view.getName()).thenReturn(name);
        when(view.getPriority()).thenReturn(7);
        doReturn(List.of(
                step(Set.of(AuthFactor.PASSWORD)),
                step(Set.of(AuthFactor.TOTP, AuthFactor.EMAIL)))).when(view).getSteps();
        when(view.getAssignedUserIds()).thenReturn(Set.of(UUID.randomUUID()));
        when(view.getAssignedRoleIds()).thenReturn(Set.of());
        return view;
    }

    private AuthPolicyStepView step(Set<AuthFactor> factors) {
        AuthPolicyStepView step = mock(AuthPolicyStepView.class);
        when(step.getAllowedFactors()).thenReturn(factors);
        return step;
    }

    @Test
    void listProjectsEachPolicyToAView() {
        AuthPolicyView policy = policy("MFA"); // hoisted: mock stubbing must not nest inside when(...)
        when(delegate.listAll()).thenReturn(List.of(policy));

        List<PolicyView> views = service.list(0, 100).items();

        assertThat(views).hasSize(1);
        PolicyView view = views.get(0);
        assertThat(view.name()).isEqualTo("MFA");
        assertThat(view.priority()).isEqualTo(7);
        assertThat(view.steps()).containsExactly(List.of("PASSWORD"), List.of("EMAIL", "TOTP"));
        assertThat(view.assignedUserIds()).hasSize(1);
    }

    @Test
    void createDelegatesAndProjectsTheResult() {
        AuthPolicySpec spec = new AuthPolicySpec("MFA", 7, true, true, true,
                List.of(Set.of(AuthFactor.PASSWORD)), Set.of(), Set.of(), 15);
        AuthPolicyView policy = policy("MFA"); // hoisted: mock stubbing must not nest inside when(...)
        when(delegate.create(spec)).thenReturn(policy);

        PolicyView view = service.create(spec);

        assertThat(view.name()).isEqualTo("MFA");
        verify(delegate).create(spec);
    }

    @Test
    void updateDelegatesAndProjectsTheResult() {
        UUID id = UUID.randomUUID();
        AuthPolicyUpdate update = new AuthPolicyUpdate(7, true, true, true,
                List.of(Set.of(AuthFactor.PASSWORD)), Set.of(), Set.of(), 15);
        AuthPolicyView policy = policy("MFA"); // hoisted: mock stubbing must not nest inside when(...)
        when(delegate.update(eq(id), any(AuthPolicyUpdate.class))).thenReturn(policy);

        PolicyView view = service.update(id, update);

        assertThat(view.name()).isEqualTo("MFA");
        verify(delegate).update(id, update);
    }

    @Test
    void deleteMerelyDelegates() {
        UUID id = UUID.randomUUID();

        service.delete(id);

        verify(delegate).delete(id);
    }
}
