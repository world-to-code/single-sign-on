package com.example.sso.auth.internal.login.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.auth.internal.login.application.PreAuthFederationSession.PendingFederation;
import com.example.sso.federation.FederatedIdentity;
import com.example.sso.federation.FederationAuthorization;
import com.example.sso.federation.FederationLoginService;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Signs a user in through a tenant's upstream OIDC provider. {@link #start} builds the authorization redirect
 * (stashing state/nonce/PKCE); {@link #complete} validates the callback, resolves or just-in-time provisions
 * the local account, and establishes the session. A federated login satisfies the PASSWORD (primary) factor —
 * any further policy factors then apply through the normal MFA flow.
 *
 * <p>Zero-trust: an account is linked or provisioned by email ONLY when the upstream marked it verified (else
 * whoever runs a rogue IdP asserting a victim's address could take the account over), the resolved user must be
 * a member of the selected tenant, and provisioning happens only when the provider allows it.
 */
@Service
@RequiredArgsConstructor
public class FederatedAuthenticationService {

    private static final String CALLBACK_TEMPLATE = "/api/auth/federation/{alias}/callback";

    private final FederationLoginService federation;
    private final PreAuthOrgSession preAuthOrg;
    private final PreAuthFederationSession preAuthFederation;
    private final FederatedUserProvisioner provisioner;
    private final FactorAuthorizationService factorAuth;
    private final AuthenticationCompletionService completionService;
    private final UserService users;
    private final OrganizationService organizations;
    private final OrgContext orgContext;
    private final AuditService audit;

    /** Begins federation for {@code alias}: returns the upstream authorization URI to redirect the browser to. */
    public String start(String alias, HttpServletRequest request) {
        UUID orgId = preAuthOrg.orgId(request)
                .orElseThrow(() -> BadRequestException.of("auth.org.selectFirst"));
        String redirectUri = ServletUriComponentsBuilder.fromContextPath(request)
                .path(CALLBACK_TEMPLATE).buildAndExpand(alias).toUriString();
        FederationAuthorization authorization = federation.beginLogin(orgId, alias, redirectUri);
        preAuthFederation.stash(request, orgId, alias, authorization.state(), authorization.nonce(),
                authorization.codeVerifier(), redirectUri);
        return authorization.authorizationUri();
    }

    /** Completes the callback: validates it, resolves/provisions the user, and establishes the session. */
    public void complete(String alias, String code, String state, HttpServletRequest request,
            HttpServletResponse response) {
        UUID orgId = preAuthOrg.orgId(request)
                .orElseThrow(() -> BadRequestException.of("auth.org.selectFirst"));
        PendingFederation pending = preAuthFederation.pending(request)
                .orElseThrow(UnauthorizedException::new); // no in-flight federation for this session
        preAuthFederation.clear(request); // single use — consume before validating so a code can't be replayed
        // Bind the callback to the request we started: the org, alias and state must all match what we stashed.
        // Pinning the org defeats a mid-flow tenant swap between /start and /callback.
        if (!pending.orgId().equals(orgId) || !pending.alias().equals(alias)
                || !constantTimeEquals(pending.state(), state) || !StringUtils.hasText(code)) {
            throw new UnauthorizedException();
        }

        FederatedIdentity identity = federation.completeLogin(orgId, alias, code, pending.redirectUri(),
                pending.nonce(), pending.codeVerifier());
        UserAccount user = resolveOrProvision(identity, orgId);

        Authentication preAuth = UsernamePasswordAuthenticationToken.authenticated(
                user.getUsername(), null, List.of()); // identified via the upstream; factors granted next
        factorAuth.establish(request, response, preAuth);
        factorAuth.grantFactor(request, response, Factors.PASSWORD); // federation satisfies the primary factor
        audit.record(new AuditRecord(AuditType.AUTH_SUCCESS, user.getUsername(), true, null, null, orgId));
        completionService.completeIfSatisfied(request, response);
    }

    /**
     * Links the federated identity to an existing tenant-OWNED member by verified email, or provisions a new
     * member when the provider allows JIT. The account must be owned by the SELECTED tenant — a tenant-controlled
     * upstream must never be able to name a global/platform (org-less) identity and sign in as it — and must be a
     * member, enabled and not locked (the same current-state re-verification the password path enforces). An
     * unverified email, a foreign/global/disabled/locked account, and a missing account with JIT off are each the
     * same generic failure, so the callback is not an account-existence oracle.
     */
    private UserAccount resolveOrProvision(FederatedIdentity identity, UUID orgId) {
        if (!identity.emailVerified() || !StringUtils.hasText(identity.email())) {
            throw new UnauthorizedException(); // an unverified address cannot be trusted to link/provision
        }
        UserAccount existing = users.findByLoginInOrg(identity.email(), orgId).orElse(null);
        if (existing != null) {
            if (!orgId.equals(existing.getOrgId()) // org-strict: never a global/platform account via tenant federation
                    || !organizations.isMember(orgId, existing.getId())
                    || !existing.isEnabled()
                    || !existing.isAccountNonLocked()
                    || existing.isTemporarilyLocked(Instant.now())) {
                throw new UnauthorizedException();
            }
            return existing;
        }
        if (!identity.jitProvisioningAllowed()) {
            throw new ForbiddenException("No account exists for this identity. Contact your administrator.");
        }
        return provision(identity, orgId);
    }

    /** Provisions atomically (see {@link FederatedUserProvisioner}) inside the tenant's RLS context. */
    private UserAccount provision(FederatedIdentity identity, UUID orgId) {
        UserAccount created = orgContext.callInOrg(orgId, () -> provisioner.provision(identity, orgId));
        audit.record(new AuditRecord(AuditType.USER_CREATED, created.getUsername(), true,
                "federated just-in-time provisioning", null, orgId));
        return created;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
