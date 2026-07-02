package com.example.sso.auth.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyEvaluator;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicyStepView;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.authpolicy.Factors;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    @InjectMocks private AuthStateService service;

    private Authentication authed(String... authorities) {
        List<SimpleGrantedAuthority> granted = List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new).toList();
        return UsernamePasswordAuthenticationToken.authenticated("alice", null, granted);
    }

    private void identifiedAlice() {
        lenient().when(user.getUsername()).thenReturn("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(policyService.resolveForUser(user)).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(true);
        lenient().when(factorHandlers.isEnrolled(eq(AuthFactor.TOTP), any())).thenReturn(true);
        lenient().when(factorHandlers.isEnrolled(eq(AuthFactor.FIDO2), any())).thenReturn(false);
    }

    @Test
    void aNullAuthenticationReportsIdentify() {
        when(policyService.defaultPolicy()).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(true);

        AuthSessionView view = service.describe(null);

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_IDENTIFY);
        assertThat(view.authenticated()).isFalse();
    }

    @Test
    void anAnonymousTokenReportsIdentify() {
        when(policyService.defaultPolicy()).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(false);
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        assertThat(service.describe(anonymous).next()).isEqualTo(AuthSessionView.NEXT_IDENTIFY);
    }

    @Test
    void anUnknownUsernameReportsIdentify() {
        when(users.findByUsername("alice")).thenReturn(Optional.empty());
        when(policyService.defaultPolicy()).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(true);

        assertThat(service.describe(authed()).next()).isEqualTo(AuthSessionView.NEXT_IDENTIFY);
    }

    @Test
    void aSatisfiedPolicyReportsDone() {
        identifiedAlice();
        when(evaluator.currentStep(eq(policy), any())).thenReturn(Optional.empty());

        AuthSessionView view = service.describe(authed(Factors.PASSWORD, Factors.TOTP));

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_DONE);
        assertThat(view.username()).isEqualTo("alice");
        assertThat(view.authenticated()).isTrue();
    }

    @Test
    void aRemainingStepReportsFactorWithChoicesOrderedByPreference() {
        identifiedAlice();
        when(evaluator.currentStep(eq(policy), any())).thenReturn(Optional.of(step));
        when(step.getAllowedFactors()).thenReturn(Set.of(AuthFactor.TOTP, AuthFactor.PASSWORD));

        AuthSessionView view = service.describe(authed(Factors.PASSWORD));

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_FACTOR);
        // PASSWORD precedes TOTP by the enum's declared preference order (not alphabetical).
        assertThat(view.pendingFactors()).containsExactly("PASSWORD", "TOTP");
    }

    @Test
    void isPolicySatisfiedIsTrueOnceComplete() {
        identifiedAlice();
        when(evaluator.currentStep(eq(policy), any())).thenReturn(Optional.empty());

        assertThat(service.isPolicySatisfied(authed(Factors.PASSWORD, Factors.TOTP))).isTrue();
    }
}
