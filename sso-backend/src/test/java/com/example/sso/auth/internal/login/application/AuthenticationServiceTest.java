package com.example.sso.auth.internal.login.application;

import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.Factors;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.SubdomainTenantResolver;
import com.example.sso.saml.SamlFrontChannelLogout;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.user.LoginResolutionScope;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the zero-trust enforcement in {@link AuthenticationService#complete}: a passwordless
 * passkey login (a {@link WebAuthnAuthentication} reaching {@code /login/webauthn}) is honored ONLY if
 * the resolved tenant still permits passwordless sign-in — re-checked server-side, never trusted from
 * the SPA's gated button.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthenticationServiceTest {

    @Mock private AuthenticationManager authenticationManager;
    @Mock private FactorAuthorizationService factorAuth;
    @Mock private AuthStateService authState;
    @Mock private AuthenticationCompletionService completionService;
    @Mock private CurrentUserProvider currentUser;
    @Mock private UserService users;
    @Mock private LoginAttemptService loginAttempts;
    @Mock private SamlFrontChannelLogout samlFrontChannel;
    @Mock private OrganizationService organizations;
    @Mock private SubdomainTenantResolver subdomainResolver;
    @Mock private PreAuthOrgSession preAuthOrg;
    @Mock private LoginResolutionScope loginScope;
    @Mock private AuditService audit;

    @InjectMocks private AuthenticationService service;

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final HttpSession session = mock(HttpSession.class);

    /** Wires a passwordless passkey session that has already selected {@code orgId} and belongs to it. */
    private void passwordlessSession(UUID orgId, UUID userId, boolean orgAllowsPasswordless) {
        WebAuthnAuthentication auth = mock(WebAuthnAuthentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("alice");
        doReturn(List.of()).when(auth).getAuthorities(); // no FIDO2 factor granted yet
        when(currentUser.authentication()).thenReturn(auth);
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(preAuthOrg.orgId(request)).thenReturn(Optional.of(orgId));
        when(request.getSession(false)).thenReturn(session);
        when(users.findByUsernameInOrg("alice", orgId)).thenReturn(Optional.of(user));
        when(organizations.isMember(orgId, userId)).thenReturn(true); // authorized for the target org
        when(organizations.isPasswordlessLoginEnabled(orgId)).thenReturn(orgAllowsPasswordless);
    }

    @Test
    void completeRejectsAndTearsDownAPasswordlessLoginWhenTheOrgDisablesIt() {
        passwordlessSession(UUID.randomUUID(), UUID.randomUUID(), false);

        assertThatThrownBy(() -> service.complete(request, response)).isInstanceOf(UnauthorizedException.class);
        verify(session).invalidate(); // the half-authenticated passkey session must not linger
        verify(factorAuth, never()).grantFactor(any(), any(), any());
        verify(completionService, never()).completeIfSatisfied(any(), any());
    }

    @Test
    void completeGrantsFido2AndFinalizesWhenTheOrgEnablesPasswordless() {
        passwordlessSession(UUID.randomUUID(), UUID.randomUUID(), true);

        service.complete(request, response);

        verify(factorAuth).grantFactor(eq(request), eq(response), eq(Factors.FIDO2));
        verify(completionService).completeIfSatisfied(request, response);
    }

    @Test
    void completeRejectsAndTearsDownAPasskeyLoginIntoAnOrgTheUserDoesNotBelongTo() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        WebAuthnAuthentication auth = mock(WebAuthnAuthentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("alice");
        when(currentUser.authentication()).thenReturn(auth);
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(preAuthOrg.orgId(request)).thenReturn(Optional.of(orgId));
        when(request.getSession(false)).thenReturn(session);
        when(users.findByUsernameInOrg("alice", orgId)).thenReturn(Optional.of(user));
        when(organizations.isMember(orgId, userId)).thenReturn(false); // NOT a member of the selected org

        assertThatThrownBy(() -> service.complete(request, response)).isInstanceOf(UnauthorizedException.class);
        verify(session).invalidate(); // the half-authenticated passkey session must not linger
        verify(factorAuth, never()).grantFactor(any(), any(), any());
        verify(completionService, never()).completeIfSatisfied(any(), any());
    }

    @Test
    void sessionAutoSelectsTheOrganizationFromATenantSubdomainSoTheOrgPickerIsSkipped() {
        when(preAuthOrg.orgId(request)).thenReturn(Optional.empty()); // nothing stashed yet
        when(request.getServerName()).thenReturn("acme.localhost");
        when(subdomainResolver.tenantSlug("acme.localhost")).thenReturn(Optional.of("acme"));
        UUID orgId = UUID.randomUUID();
        OrganizationRef org = mock(OrganizationRef.class);
        when(org.getId()).thenReturn(orgId);
        when(org.getSlug()).thenReturn("acme");
        when(org.getStatus()).thenReturn(OrganizationStatus.ACTIVE);
        when(organizations.findBySlug("acme")).thenReturn(Optional.of(org));

        service.session(request);

        verify(preAuthOrg).stash(request, orgId, "acme"); // auto-selected -> describe() will report IDENTIFY
    }

    @Test
    void sessionDoesNotAutoSelectWhenAnOrgIsAlreadyStashed() {
        when(preAuthOrg.orgId(request)).thenReturn(Optional.of(UUID.randomUUID()));

        service.session(request);

        verify(subdomainResolver, never()).tenantSlug(any());
        verify(preAuthOrg, never()).stash(any(), any(), any());
    }

    @Test
    void changePasswordSetsTheNewPasswordAndFinalizesFromTheResetState() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentUser.authentication())
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
        when(preAuthOrg.orgId(request)).thenReturn(Optional.of(orgId));
        // The session is genuinely in MUST_RESET_PASSWORD (all factors, incl. the temp password, satisfied).
        when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.mustResetPassword("alice", "acme"));
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(user.isEnabled()).thenReturn(true);
        when(user.isTemporarilyLocked(any())).thenReturn(false);
        when(users.findByUsernameInOrg("alice", orgId)).thenReturn(Optional.of(user));

        service.changePassword("new-strong-pass", request, response);

        verify(users).setPassword(userId, "new-strong-pass"); // clears the reset-required flag
        verify(completionService).completeIfSatisfied(request, response); // re-run finalizes the session
    }

    @Test
    void changePasswordIsRejectedFromTheCredentialLessIdentifyState() {
        // Pre-auth account-takeover regression: the identifier-first IDENTIFY step establishes an authenticated
        // principal with NO credential. change-password must NOT be reachable from it — only from the real
        // MUST_RESET_PASSWORD state (which requires the temporary password to have been presented).
        UUID orgId = UUID.randomUUID();
        when(currentUser.authentication())
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated("victim", null, List.of()));
        when(preAuthOrg.orgId(request)).thenReturn(Optional.of(orgId));
        when(authState.describe(any(), any(), any()))
                .thenReturn(AuthSessionView.identifyPending("acme", true, false)); // next = IDENTIFY, no credential

        assertThatThrownBy(() -> service.changePassword("attacker-pass", request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(users, never()).setPassword(any(), any());
    }
}
