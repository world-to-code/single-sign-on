package com.example.sso.auth.internal.login.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.auth.internal.login.application.PreAuthFederationSession.PendingFederation;
import com.example.sso.federation.FederatedIdentity;
import com.example.sso.federation.FederatedIdentityLinks;
import com.example.sso.federation.FederationAuthorization;
import com.example.sso.federation.FederationLoginService;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.web.ClientIp;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.role.Roles;
import com.example.sso.user.role.RoleRef;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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

    private final CompletedSessionGuard completedSession;
    private final FederationLoginService federation;
    private final FederatedIdentityLinks links;
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
        completedSession.refuseIfAlreadySignedIn();
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
        completedSession.refuseIfAlreadySignedIn();
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
        UserAccount user = resolveOrProvision(identity, orgId, ClientIp.of(request));

        Authentication preAuth = UsernamePasswordAuthenticationToken.authenticated(
                user.getUsername(), null, List.of()); // identified via the upstream; factors granted next
        factorAuth.establish(request, response, preAuth);
        factorAuth.grantFactor(request, response, Factors.PASSWORD); // federation satisfies the primary factor
        audit.record(new AuditRecord(AuditType.AUTH_SUCCESS, user.getUsername(), true, null,
                ClientIp.of(request), orgId));
        completionService.completeIfSatisfied(request, response);
    }

    /**
     * Resolves the local account, in descending order of how much it has to GUESS:
     * <ol>
     *   <li>the stable link (issuer + upstream {@code sub}) recorded by an earlier login — authoritative;</li>
     *   <li>an account the same directory provisioned under this {@code sub} (SCIM externalId) —
     *       deterministic rather than inferred, though still not privileged (see {@link #unprivileged});</li>
     *   <li>a VERIFIED-email match — an inference, and restricted accordingly (see {@link #unprivileged});</li>
     *   <li>just-in-time provisioning of a new account, when the provider allows it.</li>
     * </ol>
     * Every one of 2-4 records the link, so only the FIRST login for an identity depends on anything but the
     * subject. Email is a moving target: an upstream may rename it (matching on it would provision a duplicate
     * and orphan the account's groups and roles) or reassign it to another person; {@code sub} is what OIDC
     * keeps stable.
     *
     * <p>Every path ends at {@link #authorized}, so the link is a faster way to FIND the account, never a way to
     * skip a check. The account must be owned by the SELECTED tenant — a tenant-controlled upstream must never be
     * able to name a global/platform (org-less) identity and sign in as it — and must be a member, enabled and
     * not locked (the same current-state re-verification the password path enforces). Every refusal is rendered
     * by the controller as one identical redirect, so the callback is not an account-existence oracle.
     */
    private UserAccount resolveOrProvision(FederatedIdentity identity, UUID orgId, String clientIp) {
        UUID linked = links.findLinkedUser(orgId, identity.issuer(), identity.subject()).orElse(null);
        if (linked != null) {
            // Fails closed on a dangling link (the account was deleted) rather than falling back to email —
            // falling back would let a recycled address resolve to whoever now holds it.
            return authorized(users.findById(linked).orElseThrow(UnauthorizedException::new), orgId);
        }
        // No link yet. Before guessing by address, ask the DIRECTORY: when the same upstream provisioned this
        // account (SCIM externalId), it already recorded which local account this subject is, so there is
        // nothing to infer. This is the path a tenant should be on — it is deterministic, it survives an email
        // change, and it is how a seconded worker registered in the host company's tenant gets connected.
        UserAccount provisioned = orgContext
                .callInOrg(orgId, () -> users.findByExternalIdInOrg(identity.subject(), orgId)).orElse(null);
        if (provisioned != null) {
            // Held to the same bar as the address branch. It is tempting to trust this one more — the directory
            // ASSIGNED the identifier rather than asserting an attribute — but external_id is writable through
            // a tenant-grantable SCIM capability, so whoever can provision users can also choose which upstream
            // subject resolves to which account. That is the same admin-takeover primitive as the address
            // branch, through a different door. An administrator federates on a link created deliberately.
            return link(identity, orgId, clientIp, unprivileged(authorized(provisioned, orgId)));
        }
        // Nothing but the address left, and everything below keys on it: the match obviously, and provisioning
        // too, which creates the account under that address AND marks it verified. So an address the upstream
        // did not prove control of gets no further, whichever branch would have taken it. (A LINKED identity
        // needs no such proof: the subject is the proof.)
        if (!identity.emailVerified() || !StringUtils.hasText(identity.email())) {
            throw new UnauthorizedException();
        }
        // Matching an EXISTING account by address is an INFERENCE about who somebody is — an address can be
        // reassigned upstream — so the provider has to opt into it.
        if (identity.linkByVerifiedEmail()) {
            UserAccount existing = orgContext
                    .callInOrg(orgId, () -> users.findByLoginInOrg(identity.email(), orgId)).orElse(null);
            if (existing != null) {
                return link(identity, orgId, clientIp, unprivileged(authorized(existing, orgId)));
            }
        }
        if (!identity.jitProvisioningAllowed()) {
            throw new ForbiddenException("No account exists for this identity. Contact your administrator.");
        }
        return link(identity, orgId, clientIp, authorized(provision(identity, orgId), orgId));
    }

    /**
     * Bars a FIRST federated sign-in from claiming a PRIVILEGED account, on either bootstrap branch.
     *
     * <p>Both branches are decided by things a tenant administrator controls: whoever may register an identity
     * provider decides what the upstream asserts, and whoever may provision users decides which upstream
     * subject an account carries as its {@code external_id}. Without this bar, either capability is an
     * admin-takeover primitive — name the administrator, and the login hands over their session and their
     * roles. An ordinary account is one holding nothing but the baseline role and no direct permissions.
     *
     * <p>An existing LINK still signs in normally, however privileged: the restriction is on bootstrapping a
     * binding that nobody created deliberately, not on federating.
     */
    private UserAccount unprivileged(UserAccount account) {
        Set<String> roleNames = account.getRoles().stream().map(RoleRef::getName).collect(Collectors.toSet());
        // Fails CLOSED on a degenerate read. `role` is RLS-forced, so an account resolved without the tenant's
        // context hydrates with NO roles — and "every role is the baseline role" is vacuously true of an empty
        // set, which would wave an administrator straight through. Demand the baseline role be PRESENT, so a
        // read that saw nothing is refused rather than trusted. (The reads above are wrapped in callInOrg for
        // exactly this reason; this is the second line, because the failure mode is silent.)
        boolean ordinary = account.getDirectPermissionNames().isEmpty()
                && roleNames.equals(Set.of(Roles.USER));
        if (!ordinary) {
            throw new UnauthorizedException();
        }
        return account;
    }

    /** The current-state gates every federated login passes, however the account was found. */
    private UserAccount authorized(UserAccount account, UUID orgId) {
        if (!orgId.equals(account.getOrgId()) // org-strict: never a global/platform account via tenant federation
                || !organizations.isMember(orgId, account.getId())
                || !account.isEnabled()
                || !account.isAccountNonLocked()
                || account.isTemporarilyLocked(Instant.now())) {
            throw new UnauthorizedException();
        }
        return account;
    }

    /**
     * Records the identity→account link, for an account just gated by {@link #authorized}. Refuses when that
     * account ALREADY holds an identity at this issuer: a second subject reaching it by email means the address
     * was reassigned upstream, and honouring it would hand the previous holder's account — and roles — to
     * whoever now owns the address, permanently. Re-linking is an administrative act, not a login-time one.
     */
    private UserAccount link(FederatedIdentity identity, UUID orgId, String clientIp, UserAccount account) {
        if (links.isLinked(orgId, identity.issuer(), account.getId())) {
            audit.record(new AuditRecord(AuditType.AUTH_FAILURE, account.getUsername(), false,
                    "federated identity refused: the account is already linked to a different upstream subject",
                    clientIp, orgId));
            throw new UnauthorizedException();
        }
        if (!links.link(orgId, identity.issuer(), identity.subject(), identity.alias(), account.getId())) {
            // Lost the race the guard above normally catches: a concurrent callback linked a different subject
            // to this account first. Refuse rather than sign in on a binding we do not hold.
            throw new UnauthorizedException();
        }
        // A durable credential binding: without this record, a wrong link is invisible to an investigation.
        audit.record(new AuditRecord(AuditType.USER_UPDATED, account.getUsername(), true,
                "federated identity linked via provider " + identity.alias(), clientIp, orgId));
        return account;
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
