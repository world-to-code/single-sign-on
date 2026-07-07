package com.example.sso.auth.internal.application;

import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.Factors;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.organization.CompanyProfile;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.organization.OrganizationView;
import com.example.sso.saml.SamlFrontChannelLogout;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.user.LoginResolutionScope;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
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
    @Mock private PreAuthOrgSession preAuthOrg;
    @Mock private LoginResolutionScope loginScope;
    @Mock private AuditService audit;

    @InjectMocks private AuthenticationService service;

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);

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
        when(users.findByUsernameInOrg("alice", orgId)).thenReturn(Optional.of(user));
        when(organizations.isMember(orgId, userId)).thenReturn(true); // authorized for the target org
        when(organizations.findView(orgId)).thenReturn(Optional.of(new OrganizationView(
                orgId, "acme", "Acme", OrganizationStatus.ACTIVE, Instant.now(), CompanyProfile.empty(),
                orgAllowsPasswordless)));
    }

    @Test
    void completeRejectsAPasswordlessLoginWhenTheOrgDisablesIt() {
        passwordlessSession(UUID.randomUUID(), UUID.randomUUID(), false);

        assertThatThrownBy(() -> service.complete(request, response)).isInstanceOf(UnauthorizedException.class);
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
}
