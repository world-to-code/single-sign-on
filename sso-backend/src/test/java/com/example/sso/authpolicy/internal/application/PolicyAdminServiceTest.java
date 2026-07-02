package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyAdminService;
import com.example.sso.authpolicy.AuthPolicySpec;
import com.example.sso.authpolicy.AuthPolicyUpdate;
import com.example.sso.authpolicy.internal.domain.AuthPolicy;
import com.example.sso.authpolicy.internal.domain.AuthPolicyStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link PolicyAdminService} presentation adapter: each projecting method maps the
 * domain policy to a {@link PolicyView} (factor enums to sorted names, ids to strings) and delete
 * merely delegates. Delegation is the unit's job, so {@code verify(...)} the underlying service call.
 */
@ExtendWith(MockitoExtension.class)
class PolicyAdminServiceTest {

    @Mock private AuthPolicyAdminService delegate;

    @InjectMocks private PolicyAdminService service;

    /** A real domain policy (so PolicyView.of exercises genuine mapping) spied to carry a non-null id. */
    private AuthPolicy policy(String name) {
        AuthPolicy policy = new AuthPolicy(name, 7);
        policy.addStep(new AuthPolicyStep(1, Set.of(AuthFactor.PASSWORD)));
        policy.addStep(new AuthPolicyStep(2, Set.of(AuthFactor.TOTP, AuthFactor.EMAIL)));
        policy.assignUsers(Set.of(UUID.randomUUID()));
        AuthPolicy withId = spy(policy);
        doReturn(UUID.randomUUID()).when(withId).getId();
        return withId;
    }

    @Test
    void listProjectsEachPolicyToAView() {
        AuthPolicy policy = policy("MFA"); // hoisted: spy stubbing must not nest inside when(...)
        when(delegate.listAll()).thenReturn(List.of(policy));

        List<PolicyView> views = service.list();

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
        AuthPolicy policy = policy("MFA"); // hoisted: spy stubbing must not nest inside when(...)
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
        AuthPolicy policy = policy("MFA"); // hoisted: spy stubbing must not nest inside when(...)
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
