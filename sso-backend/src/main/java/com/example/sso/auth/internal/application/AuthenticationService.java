package com.example.sso.auth.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.Factors;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.saml.SamlFrontChannelLogout;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.shared.web.ClientIp;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.stereotype.Service;

/**
 * The core login flow: session probe, identifier-first + password sign-in, logout, the
 * post-passwordless-login finalize, and the saved-request resume target. Owns all orchestration
 * (account lookup, factor establishment, auditing, completion) so the controller stays a thin adapter.
 */
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private static final String SESSION_COOKIE = "SESSION"; // Spring Session's cookie (was JSESSIONID)
    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private final AuthenticationManager authenticationManager;
    private final FactorAuthorizationService factorAuth;
    private final AuthStateService authState;
    private final AuthenticationCompletionService completionService;
    private final CurrentUserProvider currentUser;
    private final UserService users;
    private final LoginAttemptService loginAttempts;
    private final SamlFrontChannelLogout samlFrontChannel;
    private final OrganizationService organizations;
    private final PreAuthOrgSession preAuthOrg;
    private final AuditService audit;

    public AuthSessionView session(HttpServletRequest request) {
        return authState.describe(currentUser.authentication(), preAuthOrg.orgSlug(request).orElse(null));
    }

    /**
     * Tenant-first entry: resolve the organization by slug and stash it in the pre-auth session, so the
     * subsequent identify step is scoped to it. An unknown or suspended org is rejected the same way (no
     * enumeration of which tenants exist / are active).
     */
    public AuthSessionView organization(String slug, HttpServletRequest request, HttpServletResponse response) {
        OrganizationRef org = organizations.findBySlug(slug)
                .filter(o -> o.getStatus() == OrganizationStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("No such organization."));
        preAuthOrg.stash(request, org.getId(), org.getSlug());
        audit.record(AuditType.AUTH_ORGANIZATION, org.getSlug(), true);
        return authState.describe(currentUser.authentication(), org.getSlug());
    }

    /**
     * Identifier-first: resolve the enabled account for the email and start its policy. Accounts are
     * invite-only, so an unknown/disabled email is rejected (404). This intentionally reveals account
     * existence, acceptable for an admin-managed directory.
     */
    public AuthSessionView identify(String email, HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        UUID orgId = preAuthOrg.orgId(httpRequest)
                .orElseThrow(() -> new BadRequestException("Select an organization first."));
        UserAccount user = users.findByLogin(email).filter(UserAccount::isEnabled).orElse(null);
        // Gate on membership in the resolved org. Reject a non-member the SAME way as an unknown account so
        // an attacker can't discover which emails exist (or belong to another tenant) via the login form.
        if (user == null || !organizations.isMember(orgId, user.getId())) {
            audit.record(new AuditRecord(AuditType.AUTH_IDENTIFY, email, false, "no active account in org", null));
            throw new NotFoundException("No active account for that email. Contact your administrator.");
        }

        Authentication preAuth = UsernamePasswordAuthenticationToken.authenticated(
                user.getUsername(), null, List.of()); // identified, no factors yet
        factorAuth.establish(httpRequest, httpResponse, preAuth);
        audit.record(AuditType.AUTH_IDENTIFY, user.getUsername(), true);
        return completionService.completeIfSatisfied(httpRequest, httpResponse);
    }

    /** Password sign-in; the password provider grants the password factor. Bad credentials → 401. */
    public AuthSessionView login(String username, String password, HttpServletRequest httpRequest,
                                 HttpServletResponse httpResponse) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
            factorAuth.establish(httpRequest, httpResponse, authentication);

            loginAttempts.onSuccess(username);
            audit.record(AuditType.AUTH_SUCCESS, username, true);
            return completionService.completeIfSatisfied(httpRequest, httpResponse);
        } catch (AuthenticationException e) {
            loginAttempts.onFailure(username);
            audit.record(new AuditRecord(AuditType.AUTH_FAILURE, username, false, e.getMessage(), null));
            throw new UnauthorizedException();
        }
    }

    /**
     * Logs out and returns a front-channel SAML SLO redirect URL when the session had front-channel SPs
     * (the SPA navigates there to run the redirect chain); empty otherwise. Back-channel OIDC/SAML-SOAP
     * logout happens automatically when the session is invalidated below.
     */
    public Optional<String> logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = currentUser.authentication();
        Optional<String> frontChannel = Optional.empty();
        if (authentication != null) {
            audit.record(new AuditRecord(AuditType.LOGOUT, authentication.getName(), true, null, ClientIp.of(request)));
            // Stage the front-channel chain BEFORE invalidating — invalidation clears the participant index.
            frontChannel = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith(Factors.SID_PREFIX))
                    .map(a -> a.substring(Factors.SID_PREFIX.length()))
                    .findFirst()
                    .flatMap(sid -> samlFrontChannel.startChain(sid, response));
        }
        // Invalidating the session deletes the Redis session -> SessionDestroyedEvent -> back-channel logout.
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler(SESSION_COOKIE, CSRF_COOKIE).logout(request, response, authentication);
        return frontChannel;
    }

    /**
     * Finalizes the session after Spring's passwordless {@code /login/webauthn}: records the FIDO2
     * factor (if the WebAuthn login did not tag our authority) and completes when the policy is met.
     */
    public AuthSessionView complete(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = currentUser.authentication();
        if (authentication != null && authentication.isAuthenticated()) {
            boolean hasFido2 = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).anyMatch(Factors.FIDO2::equals);
            if (authentication instanceof WebAuthnAuthentication && !hasFido2) {
                factorAuth.grantFactor(request, response, Factors.FIDO2);
            }
        }

        return completionService.completeIfSatisfied(request, response);
    }
}
