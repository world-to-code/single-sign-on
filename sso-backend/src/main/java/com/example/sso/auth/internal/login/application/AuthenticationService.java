package com.example.sso.auth.internal.login.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.Factors;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.SubdomainTenantResolver;
import com.example.sso.saml.logout.SamlFrontChannelLogout;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.shared.web.ClientIp;
import com.example.sso.user.LoginResolutionScope;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final LoginResolutionScope loginScope;
    private final SubdomainTenantResolver subdomainResolver;
    private final AuditService audit;

    public AuthSessionView session(HttpServletRequest request) {
        autoSelectOrgFromHost(request);
        return authState.describe(currentUser.authentication(),
                preAuthOrg.orgSlug(request).orElse(null), preAuthOrg.orgId(request).orElse(null));
    }

    /**
     * On a tenant subdomain ({@code {slug}.base}) the organization is unambiguous from the HOST, so auto-select
     * it — stash it exactly as the tenant-first entry would — and the SPA skips the org picker, landing on
     * IDENTIFY. Only when nothing is stashed yet and only for an ACTIVE tenant; the bare platform host derives
     * no slug, so it still shows the picker. (An unknown/suspended subdomain never reaches here — the app-chain
     * {@code TenantUnknownSubdomainGuard} already 404s it.)
     */
    private void autoSelectOrgFromHost(HttpServletRequest request) {
        if (preAuthOrg.orgId(request).isPresent()) {
            return;
        }
        subdomainResolver.tenantSlug(request.getServerName())
                .flatMap(organizations::findBySlug)
                .filter(org -> org.getStatus() == OrganizationStatus.ACTIVE)
                .ifPresent(org -> preAuthOrg.stash(request, org.getId(), org.getSlug()));
    }

    /** Whether an account has already been identified in this session (a pre-auth principal is established). */
    private boolean identified() {
        Authentication auth = currentUser.authentication();
        return auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken) && auth.getName() != null;
    }

    /**
     * Tenant-first entry: resolve the organization by slug and stash it in the pre-auth session, so the
     * subsequent identify step is scoped to it. An unknown or suspended org is rejected the same way (no
     * enumeration of which tenants exist / are active).
     */
    public AuthSessionView organization(String slug, HttpServletRequest request, HttpServletResponse response) {
        // Pin the org once an account has been identified: identify verifies membership against the stashed
        // org, so allowing a later re-selection would let a member of A switch the session to B (which login
        // completion then binds) without a membership check — a cross-tenant escape.
        if (identified()) {
            throw BadRequestException.of("auth.signin.inProgress");
        }
        // The org (the tenant) must be ACTIVE. Rejected uniformly (no enumeration of which orgs exist).
        OrganizationRef org = organizations.findBySlug(slug)
                .filter(o -> o.getStatus() == OrganizationStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("No such organization."));
        preAuthOrg.stash(request, org.getId(), org.getSlug());
        audit.record(AuditType.AUTH_ORGANIZATION, org.getSlug(), true);
        return authState.describe(currentUser.authentication(), org.getSlug(), org.getId());
    }

    /**
     * Identifier-first: resolve the enabled account for the email and start its policy. Accounts are
     * invite-only, so an unknown/disabled email is rejected (404). This intentionally reveals account
     * existence, acceptable for an admin-managed directory.
     */
    public AuthSessionView identify(String email, HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        if (!targetSelected(httpRequest)) {
            throw BadRequestException.of("auth.org.selectFirst");
        }
        UserAccount user = users.findByLoginInOrg(email, preAuthOrg.orgId(httpRequest).orElse(null))
                .filter(UserAccount::isEnabled).orElse(null);
        // Gate on the selected target — organization membership. Reject a non-member the SAME way as an unknown
        // account so an attacker can't discover which emails exist (or belong to another tenant) via the form.
        if (user == null || !authorizedForTarget(httpRequest, user.getId())) {
            audit.record(new AuditRecord(AuditType.AUTH_IDENTIFY, email, false, "no active account for the target", null));
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
        if (!targetSelected(httpRequest)) {
            throw BadRequestException.of("auth.org.selectFirst");
        }
        UUID orgId = preAuthOrg.orgId(httpRequest).orElse(null); // the selected organization (the tenant)
        try {
            // Bind the resolution org so the password provider's UserDetailsService resolves the user WITHIN
            // this tenant — a username shared across organizations must authenticate against THIS org's account
            // (falling back to a global super-admin), never another tenant's.
            Authentication authentication = loginScope.within(orgId, () -> authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password)));
            // Tenant-first: the account must be a member of the selected organization. Reject the unauthorized
            // the same way as bad credentials, so login can neither bypass tenant selection nor cross into
            // another tenant. Authorize the AUTHENTICATED principal, resolved by username exactly as the
            // credential check was — never a fresh email-first lookup that could pick a different account
            // (whose email equals this username) than the one authenticated.
            UUID userId = users.findByUsernameInOrg(authentication.getName(), orgId)
                    .map(UserAccount::getId).orElse(null);
            if (userId == null || !authorizedForTarget(httpRequest, userId)) {
                loginAttempts.onFailure(username);
                audit.record(new AuditRecord(AuditType.AUTH_FAILURE, username, false, "not authorized for the target", null, orgId));
                throw new UnauthorizedException();
            }
            factorAuth.establish(httpRequest, httpResponse, authentication);

            loginAttempts.onSuccess(username);
            audit.record(AuditType.AUTH_SUCCESS, username, true);
            return completionService.completeIfSatisfied(httpRequest, httpResponse);
        } catch (AuthenticationException e) {
            loginAttempts.onFailure(username);
            audit.record(new AuditRecord(AuditType.AUTH_FAILURE, username, false, e.getMessage(), null, orgId));
            throw new UnauthorizedException();
        }
    }

    /** Whether a tenant-first target — the organization (the tenant) — has been selected. */
    private boolean targetSelected(HttpServletRequest request) {
        return preAuthOrg.orgId(request).isPresent();
    }

    /** Whether the user is authorized for the selected target: a member of the resolved organization. */
    private boolean authorizedForTarget(HttpServletRequest request, UUID userId) {
        return preAuthOrg.orgId(request)
                .map(orgId -> organizations.isMember(orgId, userId))
                .orElse(false);
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
            // Tenant-first applies to passwordless passkey login too: the passkey authenticates the user, but
            // the session must not finalize without a resolved organization they belong to
            // (else login bypasses tenant selection via /login/webauthn). Reject the unauthorized the same way
            // as any failed sign-in.
            // Authorize the authenticated passkey principal, resolved by username within the target organization
            // (matching the completion step), so the authorized identity is provably the authenticated one.
            boolean passwordlessPasskey = authentication instanceof WebAuthnAuthentication;
            UUID orgId = preAuthOrg.orgId(request).orElse(null);
            UUID userId = users.findByUsernameInOrg(authentication.getName(), orgId)
                    .map(UserAccount::getId).orElse(null);
            if (!targetSelected(request) || userId == null || !authorizedForTarget(request, userId)) {
                throw rejectPasswordless(request, passwordlessPasskey);
            }
            // Zero-trust server enforcement: a passwordless passkey login (a WebAuthnAuthentication reached
            // /login/webauthn without a prior password) is honored ONLY if the resolved tenant still permits
            // passwordless sign-in. Re-checked here, never trusted from the SPA's gated button — the flag may
            // have been disabled after the passkey was registered, or the endpoint hit directly.
            if (passwordlessPasskey) {
                if (!organizations.isPasswordlessLoginEnabled(orgId)) {
                    throw rejectPasswordless(request, true);
                }
                boolean hasFido2 = authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).anyMatch(Factors.FIDO2::equals);
                if (!hasFido2) {
                    factorAuth.grantFactor(request, response, Factors.FIDO2);
                }
            }
        }

        return completionService.completeIfSatisfied(request, response);
    }

    /**
     * First-login password reset: the authenticated (but not-yet-finalized) user replaces the temporary
     * password an admin gave them. Only valid from the {@code MUST_RESET_PASSWORD} state — which is reached
     * ONLY once every policy factor (including the temporary PASSWORD) has been satisfied. This is NOT a
     * general "change my password" backdoor, nor may it be entered from a credential-less step: gating on
     * {@code identified()} + the flag would be exploitable, because the identifier-first IDENTIFY step
     * establishes an authenticated principal with NO credential — so an attacker could set a not-yet-activated
     * user's password without ever presenting the temporary one. Setting the password clears the flag, and
     * re-running completion finalizes the session.
     */
    public AuthSessionView changePassword(String newPassword, HttpServletRequest request,
                                          HttpServletResponse response) {
        Authentication authentication = currentUser.authentication();
        UUID orgId = preAuthOrg.orgId(request).orElse(null);
        if (!AuthSessionView.NEXT_MUST_RESET_PASSWORD.equals(
                authState.describe(authentication, null, orgId).next())) {
            throw new UnauthorizedException();
        }
        UserAccount user = users.findByUsernameInOrg(authentication.getName(), orgId)
                .orElseThrow(UnauthorizedException::new);
        // Zero-trust: re-verify the account is still live (enabled, not locked) before writing the password.
        if (!user.isEnabled() || user.isTemporarilyLocked(Instant.now())) {
            throw new UnauthorizedException();
        }
        users.setPassword(user.getId(), newPassword); // clears the reset-required flag
        return completionService.completeIfSatisfied(request, response); // now finalizes → DONE
    }

    /**
     * Builds the rejection for a failed {@link #complete}. For a passwordless passkey session the
     * {@code /login/webauthn} filter has ALREADY persisted a {@link WebAuthnAuthentication} in the session,
     * so tear that half-authenticated session down (invalidate + clear context) — otherwise it would linger
     * until expiry and could be re-submitted to {@code /api/auth/complete} if the org later re-enables
     * passwordless (ceremony-banking), completing without a fresh passkey assertion.
     */
    private UnauthorizedException rejectPasswordless(HttpServletRequest request, boolean passwordlessPasskey) {
        if (passwordlessPasskey) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
        }
        return new UnauthorizedException();
    }
}
