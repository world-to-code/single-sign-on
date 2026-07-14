package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyEvaluator;
import com.example.sso.authpolicy.policy.AuthPolicyStepView;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.portal.access.AppAccess;
import com.example.sso.portal.access.AppAccessQuery;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Unit tests for {@link AppAccessResolver}: it delegates the per-app sign-on policy resolution to the
 * {@code policy_binding} matrix ({@link PolicyBindingResolver#resolveAuthPolicy}) and then runs the factor +
 * step-up-freshness gate; {@code setAppPolicy} delegates the app-wide write to {@link AppAuthBinding}. The
 * resolution/specificity semantics themselves are covered by {@code PolicyBindingResolverIT}.
 */
class AppAccessResolverTest {

    private static final AppType APP_TYPE = AppType.OIDC;
    private static final String APP_ID = "app1";

    private PolicyBindingResolver bindings;
    private AppAuthBinding appAuthBinding;
    private AuthPolicyEvaluator evaluator;
    private AppAccessResolver resolver;

    @BeforeEach
    void setUp() {
        bindings = mock(PolicyBindingResolver.class);
        appAuthBinding = mock(AppAuthBinding.class);
        evaluator = mock(AuthPolicyEvaluator.class);
        resolver = new AppAccessResolver(bindings, appAuthBinding, evaluator);
    }

    @Test
    void noResolvedPolicyGrantsImmediateAccess() {
        UserAccount user = mock(UserAccount.class);
        when(bindings.resolveAuthPolicy(user, APP_TYPE, APP_ID)).thenReturn(Optional.empty());

        AppAccess access = resolver.appAccess(query(user, null));

        assertThat(access.ready()).isTrue();
        assertThat(access.pendingFactors()).isEmpty();
        verify(evaluator, never()).currentStep(any(), any());
    }

    @Test
    void missingFactorReturnsPendingFactorsSortedByOrdinal() {
        UserAccount user = withPolicy(mock(AuthPolicyView.class));
        AuthPolicyStepView missing = step(AuthFactor.TOTP, AuthFactor.PASSWORD); // build before stubbing (no nesting)
        when(evaluator.currentStep(any(), any())).thenReturn(Optional.of(missing));

        AppAccess access = resolver.appAccess(query(user, null));

        assertThat(access.ready()).isFalse();
        assertThat(access.pendingFactors()).containsExactly("PASSWORD", "TOTP");
    }

    @Test
    void allFactorsHeldWithFreshStepUpGrantsAccess() {
        AuthPolicyView policy = mock(AuthPolicyView.class);
        when(policy.getStepUpFreshnessMinutes()).thenReturn(5);
        doReturn(List.of(step(AuthFactor.FIDO2))).when(policy).getSteps(); // non-empty: grant only via freshness
        UserAccount user = withPolicy(policy);
        when(evaluator.currentStep(any(), any())).thenReturn(Optional.empty());

        AppAccess access = resolver.appAccess(query(user, Instant.now().minus(1, ChronoUnit.MINUTES)));

        assertThat(access.ready()).isTrue();
    }

    @Test
    void allFactorsHeldButStaleStepUpRequiresTheFinalStep() {
        AuthPolicyView policy = mock(AuthPolicyView.class);
        when(policy.getStepUpFreshnessMinutes()).thenReturn(5);
        doReturn(List.of(step(AuthFactor.PASSWORD), step(AuthFactor.FIDO2))).when(policy).getSteps();
        UserAccount user = withPolicy(policy);
        when(evaluator.currentStep(any(), any())).thenReturn(Optional.empty());

        AppAccess access = resolver.appAccess(query(user, Instant.now().minus(10, ChronoUnit.MINUTES)));

        assertThat(access.ready()).isFalse();
        assertThat(access.pendingFactors()).containsExactly("FIDO2");
    }

    @Test
    void allFactorsHeldWithNoStepsGrantsAccessEvenWithoutFreshStepUp() {
        AuthPolicyView policy = mock(AuthPolicyView.class);
        when(policy.getStepUpFreshnessMinutes()).thenReturn(5);
        when(policy.getSteps()).thenReturn(List.of());
        UserAccount user = withPolicy(policy);
        when(evaluator.currentStep(any(), any())).thenReturn(Optional.empty());

        assertThat(resolver.appAccess(query(user, null)).ready()).isTrue();
    }

    @Test
    void setAppPolicyWithBlankIdClearsTheAppWideBinding() {
        resolver.setAppPolicy(APP_TYPE, APP_ID, "  ");
        verify(appAuthBinding).setAppWide(APP_TYPE, APP_ID, null);
    }

    @Test
    void setAppPolicyDelegatesTheParsedPolicyId() {
        UUID policyId = UUID.randomUUID();
        resolver.setAppPolicy(APP_TYPE, APP_ID, policyId.toString());
        verify(appAuthBinding).setAppWide(APP_TYPE, APP_ID, policyId);
    }

    @Test
    void setAppPolicyRejectsAPortalAppTypeWithoutWriting() {
        // A portal is not a catalog app — no per-app sign-on binding may be written on it (else it is an orphan
        // nothing resolves), so the write path rejects PORTAL just as assignment does.
        assertThatThrownBy(() -> resolver.setAppPolicy(AppType.PORTAL, "admin", UUID.randomUUID().toString()))
                .isInstanceOf(BadRequestException.class);
        verify(appAuthBinding, never()).setAppWide(any(), any(), any());
    }

    private UserAccount withPolicy(AuthPolicyView policy) {
        UserAccount user = mock(UserAccount.class);
        when(bindings.resolveAuthPolicy(user, APP_TYPE, APP_ID)).thenReturn(Optional.of(policy));
        return user;
    }

    private AppAccessQuery query(UserAccount user, Instant lastAppStepUp) {
        return new AppAccessQuery(user, APP_TYPE, APP_ID, Set.of("FACTOR_PASSWORD"), lastAppStepUp);
    }

    private AuthPolicyStepView step(AuthFactor... factors) {
        AuthPolicyStepView step = mock(AuthPolicyStepView.class);
        when(step.getAllowedFactors()).thenReturn(Set.of(factors));
        return step;
    }
}
