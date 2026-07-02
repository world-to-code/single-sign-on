package com.example.sso.auth.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.Factors;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
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

    private static final String SESSION_COOKIE = "JSESSIONID";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private final AuthenticationManager authenticationManager;
    private final FactorAuthorizationService factorAuth;
    private final AuthStateService authState;
    private final AuthenticationCompletionService completionService;
    private final CurrentUserProvider currentUser;
    private final UserService users;
    private final LoginAttemptService loginAttempts;
    private final AuditService audit;

    public AuthSessionView session() {
        return authState.describe(currentUser.authentication());
    }

    /**
     * Identifier-first: resolve the enabled account for the email and start its policy. Accounts are
     * invite-only, so an unknown/disabled email is rejected (404). This intentionally reveals account
     * existence, acceptable for an admin-managed directory.
     */
    public AuthSessionView identify(String email, HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        UserAccount user = users.findByLogin(email).filter(UserAccount::isEnabled).orElse(null);
        if (user == null) {
            audit.record(new AuditRecord(AuditType.AUTH_IDENTIFY, email, false, "no active account", null));
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

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = currentUser.authentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler(SESSION_COOKIE, CSRF_COOKIE).logout(request, response, authentication);
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
