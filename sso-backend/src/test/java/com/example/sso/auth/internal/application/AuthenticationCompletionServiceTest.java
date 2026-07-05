package com.example.sso.auth.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.Factors;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.session.SessionLifecycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthenticationCompletionService}: it promotes a session to fully-authenticated
 * exactly once — only when the policy is satisfied and MFA_COMPLETE is not yet present. Promotion is a
 * cluster of collaborator side effects (establish + register + audit), so those are asserted with
 * {@code verify(...)}; the no-op paths verify NO establish/register happen.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationCompletionServiceTest {

    @Mock private AuthStateService authState;
    @Mock private UserDetailsService userDetailsService;
    @Mock private FactorAuthorizationService factorAuth;
    @Mock private SessionLifecycle sessions;
    // Real (not mocked) so its Optional-returning reads work against the session-less MockHttpServletRequest.
    @Spy private PreAuthOrgSession preAuthOrg = new PreAuthOrgSession();
    @Mock private AuditService audit;

    @InjectMocks private AuthenticationCompletionService service;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(String... authorities) {
        List<SimpleGrantedAuthority> granted = List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new).toList();
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated("alice", null, granted);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void anUnauthenticatedContextIsNeverPromoted() {
        SecurityContextHolder.clearContext();
        when(authState.describe(any(), any())).thenReturn(AuthSessionView.organizationPending(true));

        service.completeIfSatisfied(request, response);

        verify(factorAuth, never()).establish(any(), any(), any());
        verify(sessions, never()).registerAndEnforceLimit(any(), any());
    }

    @Test
    void anAlreadyCompleteSessionIsNotPromotedAgain() {
        signIn(Factors.PASSWORD, Factors.MFA_COMPLETE);
        when(authState.describe(any(), any())).thenReturn(AuthSessionView.organizationPending(true));

        service.completeIfSatisfied(request, response);

        verify(factorAuth, never()).establish(any(), any(), any());
        verify(sessions, never()).registerAndEnforceLimit(any(), any());
    }

    @Test
    void aSatisfiedSessionIsPromotedRegisteredAndAudited() {
        signIn(Factors.PASSWORD, Factors.TOTP);
        when(authState.isPolicySatisfied(any())).thenReturn(true);
        when(userDetailsService.loadUserByUsername("alice"))
                .thenReturn(new User("alice", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(authState.describe(any(), any())).thenReturn(AuthSessionView.organizationPending(true));

        service.completeIfSatisfied(request, response);

        verify(factorAuth).establish(eq(request), eq(response), any());
        verify(sessions).registerAndEnforceLimit(request, "alice");
        verify(audit).record(any(AuditRecord.class));
    }

    @Test
    void thePromotedAuthenticationCarriesAFactorGrantedAuthorityForOidcAuthTime() {
        signIn(Factors.PASSWORD, Factors.TOTP);
        when(authState.isPolicySatisfied(any())).thenReturn(true);
        when(userDetailsService.loadUserByUsername("alice"))
                .thenReturn(new User("alice", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(authState.describe(any(), any())).thenReturn(AuthSessionView.organizationPending(true));

        service.completeIfSatisfied(request, response);

        // Spring AS derives the OIDC auth_time from a FactorGrantedAuthority; without one the token endpoint 500s.
        ArgumentCaptor<Authentication> promoted = ArgumentCaptor.forClass(Authentication.class);
        verify(factorAuth).establish(eq(request), eq(response), promoted.capture());
        assertThat(promoted.getValue().getAuthorities()).anyMatch(FactorGrantedAuthority.class::isInstance);
    }
}
