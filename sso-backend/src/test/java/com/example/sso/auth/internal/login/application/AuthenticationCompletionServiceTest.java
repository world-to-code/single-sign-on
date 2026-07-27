package com.example.sso.auth.internal.login.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.session.lifecycle.SessionLifecycle;
import com.example.sso.user.account.LoginResolutionScope;
import org.junit.jupiter.api.AfterEach;
import com.example.sso.shared.error.UnauthorizedException;
import java.util.Optional;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import com.example.sso.tenancy.OrgContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Mock private OrgContext orgContext;
    @Mock private AuditService audit;
    // Real (a spy) so within(...) actually runs the wrapped loadUserByUsername supplier.
    @Spy private LoginResolutionScope loginScope = new LoginResolutionScope();

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
        when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.organizationPending(true));

        service.completeIfSatisfied(request, response);

        verify(factorAuth, never()).establish(any(), any(), any());
        verify(sessions, never()).registerAndEnforceLimit(any(), any());
    }

    @Test
    void anAlreadyCompleteSessionIsNotPromotedAgain() {
        signIn(Factors.PASSWORD, Factors.MFA_COMPLETE);
        when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.organizationPending(true));

        service.completeIfSatisfied(request, response);

        verify(factorAuth, never()).establish(any(), any(), any());
        verify(sessions, never()).registerAndEnforceLimit(any(), any());
    }

    @Test
    void aSatisfiedSessionIsPromotedRegisteredAndAudited() {
        signIn(Factors.PASSWORD, Factors.TOTP);
        when(authState.isPolicySatisfied(any(), any())).thenReturn(true);
        when(userDetailsService.loadUserByUsername("alice"))
                .thenReturn(new User("alice", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.organizationPending(true));

        service.completeIfSatisfied(request, response);

        verify(factorAuth).establish(eq(request), eq(response), any());
        verify(sessions).registerAndEnforceLimit(request, "alice");
        verify(audit).record(any(AuditRecord.class));
    }

    // --- the login org: an explicit input for callers that have no session to read it from ------------

    @Test
    void aProvenOrgIsUsedWhenTheRequestCarriesNone() {
        // The inbound SAML ACS is a cross-site POST, so a SameSite=Lax session cookie is not sent and every
        // session-derived value here is empty. Falling back to "no org" would skip the promotion entirely for a
        // tenant account — or resolve a GLOBAL namesake, handing out the platform super-admin's authorities.
        UUID provenOrg = UUID.randomUUID();
        signIn(Factors.PASSWORD, Factors.TOTP);
        when(preAuthOrg.orgId(request)).thenReturn(Optional.empty());
        when(authState.isPolicySatisfied(any(), eq(provenOrg))).thenReturn(true);
        when(orgContext.callInOrg(eq(provenOrg), any())).thenAnswer(invocation ->
                invocation.<java.util.function.Supplier<?>>getArgument(1).get());
        when(userDetailsService.loadUserByUsername("alice"))
                .thenReturn(new User("alice", "", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.organizationPending(true));

        service.completeIfSatisfied(request, response, provenOrg);

        ArgumentCaptor<Authentication> promoted = ArgumentCaptor.captor();
        verify(factorAuth).establish(eq(request), eq(response), promoted.capture());
        assertThat(promoted.getValue().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains(Factors.ORG_PREFIX + provenOrg); // the session IS tenant-bound, so it is revocable
    }

    @Test
    void aRequestOrgThatDisagreesWithTheProvenOrgIsRefused() {
        // A login started for one tenant must not be completed under another by re-selecting the organization
        // in a second tab mid-flow — the pin the OIDC callback gets from comparing its stashed org.
        UUID provenOrg = UUID.randomUUID();
        signIn(Factors.PASSWORD, Factors.TOTP);
        when(preAuthOrg.orgId(request)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service.completeIfSatisfied(request, response, provenOrg))
                .isInstanceOf(UnauthorizedException.class);
        verify(factorAuth, never()).establish(any(), any(), any());
    }

    @Test
    void thePromotedAuthenticationCarriesAFactorGrantedAuthorityForOidcAuthTime() {
        signIn(Factors.PASSWORD, Factors.TOTP);
        when(authState.isPolicySatisfied(any(), any())).thenReturn(true);
        when(userDetailsService.loadUserByUsername("alice"))
                .thenReturn(new User("alice", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.organizationPending(true));

        service.completeIfSatisfied(request, response);

        // Spring AS derives the OIDC auth_time from a FactorGrantedAuthority; without one the token endpoint 500s.
        ArgumentCaptor<Authentication> promoted = ArgumentCaptor.forClass(Authentication.class);
        verify(factorAuth).establish(eq(request), eq(response), promoted.capture());
        assertThat(promoted.getValue().getAuthorities()).anyMatch(FactorGrantedAuthority.class::isInstance);
    }

    /**
     * Promotion REBUILDS the authority set from the freshly loaded principal, keeping only what it explicitly
     * copies. The federated marker is deliberately not FACTOR_-prefixed (that prefix is counted as a factor),
     * so the factor filter alone drops it — and the token then reports amr=pwd, telling every relying party
     * this IdP verified a password it never saw.
     */
    @Test
    void thePromotedAuthenticationCarriesTheFederatedMarker() {
        signIn(Factors.PASSWORD, Factors.FEDERATED);
        when(authState.isPolicySatisfied(any(), any())).thenReturn(true);
        when(userDetailsService.loadUserByUsername("alice"))
                .thenReturn(new User("alice", "", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.organizationPending(true));

        service.completeIfSatisfied(request, response);

        ArgumentCaptor<Authentication> promoted = ArgumentCaptor.forClass(Authentication.class);
        verify(factorAuth).establish(eq(request), eq(response), promoted.capture());
        assertThat(promoted.getValue().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains(Factors.FEDERATED);
    }

    /** ...and never invents it: an ordinary password login must not look federated to a relying party. */
    @Test
    void anOrdinaryLoginIsNotMarkedFederated() {
        signIn(Factors.PASSWORD);
        when(authState.isPolicySatisfied(any(), any())).thenReturn(true);
        when(userDetailsService.loadUserByUsername("alice"))
                .thenReturn(new User("alice", "", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.organizationPending(true));

        service.completeIfSatisfied(request, response);

        ArgumentCaptor<Authentication> promoted = ArgumentCaptor.forClass(Authentication.class);
        verify(factorAuth).establish(eq(request), eq(response), promoted.capture());
        assertThat(promoted.getValue().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .doesNotContain(Factors.FEDERATED);
    }
}
