package com.example.sso.auth.internal.login.application;

import com.example.sso.auth.internal.factor.application.FactorHandlers;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyEvaluator;
import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.authpolicy.policy.AuthPolicyStepView;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.organization.OrganizationService;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthStateService#describe}: it reports IDENTIFY for anonymous/unknown callers,
 * DONE once the resolved policy is satisfied, and FACTOR (with the next step's choices ordered by the
 * factor's natural preference) while a step remains.
 */
@ExtendWith(MockitoExtension.class)
class AuthStateServiceTest {

    @Mock private UserService users;
    @Mock private FactorHandlers factorHandlers;
    @Mock private AuthPolicyResolver policyService;
    @Mock private AuthPolicyEvaluator evaluator;
    @Mock private AuthPolicyView policy;
    @Mock private AuthPolicyStepView step;
    @Mock private UserAccount user;
    @Mock private LoginPolicyResolver loginPolicy;
    @Mock private OrganizationService organizations;

    @InjectMocks private AuthStateService service;

    private Authentication authed(String... authorities) {
        List<SimpleGrantedAuthority> granted = List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new).toList();
        return UsernamePasswordAuthenticationToken.authenticated("alice", null, granted);
    }

    private void identifiedAlice() {
        lenient().when(user.getUsername()).thenReturn("alice");
        when(users.findByUsernameInOrg(eq("alice"), any())).thenReturn(Optional.of(user));
        when(loginPolicy.resolve(eq(user), any())).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(true);
        lenient().when(factorHandlers.isEnrolled(eq(AuthFactor.TOTP), any())).thenReturn(true);
        lenient().when(factorHandlers.isEnrolled(eq(AuthFactor.FIDO2), any())).thenReturn(false);
    }

    @Test
    void aNullAuthenticationWithNoOrgReportsOrganization() {
        when(policyService.defaultPolicy()).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(true);

        AuthSessionView view = service.describe(null, null, null);

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_ORGANIZATION);
        assertThat(view.authenticated()).isFalse();
        assertThat(view.org()).isNull();
    }

    @Test
    void aResolvedOrgWithNoUserReportsIdentify() {
        when(policyService.defaultPolicy()).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(true);

        AuthSessionView view = service.describe(null, "acme", null);

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_IDENTIFY);
        assertThat(view.org()).isEqualTo("acme");
    }

    @Test
    void anAnonymousTokenWithNoOrgReportsOrganization() {
        when(policyService.defaultPolicy()).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(false);
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        assertThat(service.describe(anonymous, null, null).next()).isEqualTo(AuthSessionView.NEXT_ORGANIZATION);
    }

    @Test
    void anUnknownUsernameWithNoOrgReportsOrganization() {
        when(users.findByUsernameInOrg(eq("alice"), any())).thenReturn(Optional.empty());
        when(policyService.defaultPolicy()).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(true);

        assertThat(service.describe(authed(), null, null).next()).isEqualTo(AuthSessionView.NEXT_ORGANIZATION);
    }

    @Test
    void aSatisfiedPolicyReportsDoneCarryingTheActiveOrg() {
        identifiedAlice();
        when(evaluator.currentStep(eq(policy), any())).thenReturn(Optional.empty());

        AuthSessionView view = service.describe(authed(Factors.PASSWORD, Factors.TOTP), "acme", null);

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_DONE);
        assertThat(view.username()).isEqualTo("alice");
        assertThat(view.authenticated()).isTrue();
        assertThat(view.org()).isEqualTo("acme");
    }

    @Test
    void aUserWithATemporaryPasswordReportsMustResetInsteadOfDone() {
        identifiedAlice();
        when(user.isPasswordResetRequired()).thenReturn(true); // admin-issued temporary password
        when(evaluator.currentStep(eq(policy), any())).thenReturn(Optional.empty()); // all factors satisfied

        AuthSessionView view = service.describe(authed(Factors.PASSWORD, Factors.TOTP), "acme", null);

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_MUST_RESET_PASSWORD);
        assertThat(view.authenticated()).isFalse();
        assertThat(view.username()).isEqualTo("alice");
    }

    @Test
    void aRemainingStepReportsFactorWithChoicesOrderedByPreference() {
        identifiedAlice();
        when(evaluator.currentStep(eq(policy), any())).thenReturn(Optional.of(step));
        when(step.getAllowedFactors()).thenReturn(Set.of(AuthFactor.TOTP, AuthFactor.PASSWORD));

        AuthSessionView view = service.describe(authed(Factors.PASSWORD), "acme", null);

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_FACTOR);
        // PASSWORD precedes TOTP by the enum's declared preference order (not alphabetical).
        assertThat(view.pendingFactors()).containsExactly("PASSWORD", "TOTP");
    }

    @Test
    void reportsPasswordlessLoginAllowedWhenTheSelectedOrgEnablesIt() {
        UUID loginOrg = UUID.randomUUID();
        when(policyService.defaultPolicy()).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(true);
        when(organizations.isPasswordlessLoginEnabled(loginOrg)).thenReturn(true);

        AuthSessionView view = service.describe(null, "acme", loginOrg); // org selected, not yet identified

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_IDENTIFY);
        assertThat(view.passwordlessLoginAllowed()).isTrue();
    }

    @Test
    void reportsPasswordlessLoginDisallowedWhenTheSelectedOrgDisablesIt() {
        UUID loginOrg = UUID.randomUUID();
        when(policyService.defaultPolicy()).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(true);
        when(organizations.isPasswordlessLoginEnabled(loginOrg)).thenReturn(false);

        assertThat(service.describe(null, "acme", loginOrg).passwordlessLoginAllowed()).isFalse();
    }

    @Test
    void isPolicySatisfiedIsTrueOnceComplete() {
        identifiedAlice();
        when(evaluator.currentStep(eq(policy), any())).thenReturn(Optional.empty());

        assertThat(service.isPolicySatisfied(authed(Factors.PASSWORD, Factors.TOTP), null)).isTrue();
    }

    @Test
    void threadsTheLoginOrgToThePolicyResolver() {
        // The org-scoped binding/RLS resolution itself lives in LoginPolicyResolver (tested there); here we
        // only assert the login org the caller resolved is passed through so tenant policies can participate.
        UUID loginOrg = UUID.randomUUID();
        identifiedAlice();
        when(evaluator.currentStep(eq(policy), any())).thenReturn(Optional.empty());

        service.describe(authed(Factors.PASSWORD, Factors.TOTP), "acme", loginOrg);

        verify(loginPolicy).resolve(user, loginOrg);
    }
}
