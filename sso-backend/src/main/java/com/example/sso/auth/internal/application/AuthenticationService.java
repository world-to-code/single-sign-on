package com.example.sso.auth.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.Factors;
import com.example.sso.customer.CustomerRef;
import com.example.sso.customer.CustomerStatus;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.customer.CustomerService;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.saml.SamlFrontChannelLogout;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.shared.web.ClientIp;
import com.example.sso.user.LoginResolutionScope;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final CustomerService customers;
    private final PreAuthOrgSession preAuthOrg;
    private final PreAuthCustomerSession preAuthCustomer;
    private final LoginTargetCustomer targetCustomer;
    private final LoginResolutionScope loginScope;
    private final AuditService audit;

    public AuthSessionView session(HttpServletRequest request) {
        // Show whichever tenant-first target was selected — a customer (고객사) console or an org. A customer
        // login has no org, so its policy is the default (loginOrgId = null).
        Optional<String> customerSlug = preAuthCustomer.customerSlug(request);
        if (customerSlug.isPresent()) {
            return authState.describe(currentUser.authentication(), customerSlug.get(), null);
        }
        return authState.describe(currentUser.authentication(),
                preAuthOrg.orgSlug(request).orElse(null), preAuthOrg.orgId(request).orElse(null));
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
            throw new BadRequestException("Sign-in is already in progress; restart to change organization.");
        }
        // The org must be ACTIVE and so must its parent customer (고객사) — suspending a customer gates login
        // to all of its branches. Rejected uniformly (no enumeration of which orgs/customers exist).
        OrganizationRef org = organizations.findBySlug(slug)
                .filter(o -> o.getStatus() == OrganizationStatus.ACTIVE)
                .filter(o -> customers.isActive(o.getCustomerId()))
                .orElseThrow(() -> new NotFoundException("No such organization."));
        preAuthCustomer.clear(request); // an org selection and a customer selection are mutually exclusive
        preAuthOrg.stash(request, org.getId(), org.getSlug());
        audit.record(AuditType.AUTH_ORGANIZATION, org.getSlug(), true);
        return authState.describe(currentUser.authentication(), org.getSlug(), org.getId());
    }

    /**
     * Customer-first entry: resolve the customer (고객사) by slug and stash it, so the subsequent identify step
     * gates on customer-admin membership and login completion mints a {@code CUSTOMER_} console session. This is
     * how a company signs in to its workspace BEFORE any org exists (a customer-admin manages orgs from the
     * console). An unknown or suspended customer is rejected uniformly (no enumeration of which workspaces exist).
     */
    public AuthSessionView customer(String slug, HttpServletRequest request, HttpServletResponse response) {
        if (identified()) {
            throw new BadRequestException("Sign-in is already in progress; restart to change workspace.");
        }
        CustomerRef customer = customers.findBySlug(slug)
                .filter(c -> c.getStatus() == CustomerStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("No such workspace."));
        preAuthOrg.clear(request); // a customer selection and an org selection are mutually exclusive
        preAuthCustomer.stash(request, customer.getId(), customer.getSlug());
        audit.record(AuditType.AUTH_CUSTOMER, customer.getSlug(), true);
        return authState.describe(currentUser.authentication(), customer.getSlug(), null);
    }

    /**
     * Identifier-first: resolve the enabled account for the email and start its policy. Accounts are
     * invite-only, so an unknown/disabled email is rejected (404). This intentionally reveals account
     * existence, acceptable for an admin-managed directory.
     */
    public AuthSessionView identify(String email, HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        if (!targetSelected(httpRequest)) {
            throw new BadRequestException("Select an organization first.");
        }
        UserAccount user = users.findByLoginInCustomer(email, targetCustomer.of(httpRequest))
                .filter(UserAccount::isEnabled).orElse(null);
        // Gate on the selected target — org membership, or customer-admin membership for a console login. Reject
        // a non-member the SAME way as an unknown account so an attacker can't discover which emails exist (or
        // belong to another tenant) via the login form.
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
            throw new BadRequestException("Select an organization first.");
        }
        UUID orgId = preAuthOrg.orgId(httpRequest).orElse(null); // audit tenant tag (null for a customer login)
        UUID customerId = targetCustomer.of(httpRequest); // resolve the user within the selected target's customer
        try {
            // Bind the resolution customer so the password provider's UserDetailsService resolves the user
            // WITHIN this tenant — a username shared across customers must authenticate against THIS
            // customer's account (falling back to a global super-admin), never another tenant's.
            Authentication authentication = loginScope.within(customerId, () -> authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password)));
            // Tenant-first: the account must be authorized for the selected target (an org member, or a
            // customer admin). Reject the unauthorized the same way as bad credentials, so login can neither
            // bypass tenant selection nor cross into another tenant.
            UUID userId = users.findByLoginInCustomer(username, customerId).map(UserAccount::getId).orElse(null);
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

    /** Whether a tenant-first target — an organization or a customer (고객사) console — has been selected. */
    private boolean targetSelected(HttpServletRequest request) {
        return preAuthOrg.orgId(request).isPresent() || preAuthCustomer.customerId(request).isPresent();
    }

    /** Whether the user is authorized for the selected target: a member of the resolved org, or an
     *  administrator of the resolved customer (for a console login). */
    private boolean authorizedForTarget(HttpServletRequest request, UUID userId) {
        Optional<UUID> org = preAuthOrg.orgId(request);
        if (org.isPresent()) {
            return organizations.isMember(org.get(), userId);
        }
        return preAuthCustomer.customerId(request)
                .map(customerId -> customers.isCustomerAdmin(userId, customerId))
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
            // the session must not finalize without a resolved target (org or customer console) they belong to
            // (else login bypasses tenant selection via /login/webauthn). Reject the unauthorized the same way
            // as any failed sign-in.
            UUID userId = users.findByLoginInCustomer(authentication.getName(), targetCustomer.of(request))
                    .map(UserAccount::getId).orElse(null);
            if (!targetSelected(request) || userId == null || !authorizedForTarget(request, userId)) {
                throw new UnauthorizedException();
            }
            boolean hasFido2 = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).anyMatch(Factors.FIDO2::equals);
            if (authentication instanceof WebAuthnAuthentication && !hasFido2) {
                factorAuth.grantFactor(request, response, Factors.FIDO2);
            }
        }

        return completionService.completeIfSatisfied(request, response);
    }
}
