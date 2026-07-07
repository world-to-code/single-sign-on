package com.example.sso.auth.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyEvaluator;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicyStepView;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.authpolicy.Factors;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    @Mock private OrgContext orgContext;

    @InjectMocks private AuthStateService service;

    private Authentication authed(String... authorities) {
        List<SimpleGrantedAuthority> granted = List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new).toList();
        return UsernamePasswordAuthenticationToken.authenticated("alice", null, granted);
    }

    private void identifiedAlice() {
        lenient().when(user.getUsername()).thenReturn("alice");
        when(users.findByUsernameInOrg(eq("alice"), any())).thenReturn(Optional.of(user));
        when(policyService.resolveForUser(user)).thenReturn(policy);
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
    void isPolicySatisfiedIsTrueOnceComplete() {
        identifiedAlice();
        when(evaluator.currentStep(eq(policy), any())).thenReturn(Optional.empty());

        assertThat(service.isPolicySatisfied(authed(Factors.PASSWORD, Factors.TOTP), null)).isTrue();
    }

    @Test
    void withNoLoginOrgResolutionNeverBindsAnOrgContext() {
        identifiedAlice();
        when(evaluator.currentStep(eq(policy), any())).thenReturn(Optional.empty());

        service.describe(authed(Factors.PASSWORD, Factors.TOTP), "acme", null);

        // No org resolved yet (pre-org step / post-login step-up) → must resolve the global/default policy
        // WITHOUT binding any tenant context (binding an unverified org would be a cross-tenant risk).
        verify(orgContext, never()).callInOrg(any(), any());
    }

    @Test
    void policyResolutionBindsTheLoginOrgSoTenantPoliciesApply() {
        UUID loginOrg = UUID.randomUUID();
        identifiedAlice();
        // Bind the login org around resolution so the tenant's own (RLS-scoped) auth policies participate.
        when(orgContext.callInOrg(eq(loginOrg), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(evaluator.currentStep(eq(policy), any())).thenReturn(Optional.empty());

        AuthSessionView view = service.describe(authed(Factors.PASSWORD, Factors.TOTP), "acme", loginOrg);

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_DONE);
        verify(orgContext).callInOrg(eq(loginOrg), any());
    }
}
