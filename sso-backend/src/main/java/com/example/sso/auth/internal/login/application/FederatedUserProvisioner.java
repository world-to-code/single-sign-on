package com.example.sso.auth.internal.login.application;

import com.example.sso.federation.FederatedIdentity;
import com.example.sso.organization.OrganizationService;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.OwnershipChallenge;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Just-in-time provisioning of a federated user, as ONE transaction: create the account, make it a member of
 * the tenant, and mark its email verified ONLY when the upstream actually proved the address.
 * Atomicity matters — if these were
 * separate transactions and membership failed after the account committed, the orphaned account would exist as
 * a non-member, and every future federated login would take the "existing account, not a member" branch and be
 * rejected forever with no path to re-provision. Invoked inside {@code orgContext.callInOrg(orgId, …)} so the
 * single tx and the RLS context agree on the org.
 */
@Component
@RequiredArgsConstructor
class FederatedUserProvisioner {

    private static final String DEFAULT_ROLE = "ROLE_USER";

    private final UserService users;
    private final OrganizationService organizations;

    @Transactional
    UserAccount provision(FederatedIdentity identity, UUID orgId) {
        // Normalized FIRST: the address is the account's name, and the org-unique indexes are case-sensitive,
        // so "CEO@corp.example" and "ceo@corp.example" would otherwise be two accounts that every downstream
        // SP treats as one person.
        String username = identity.email().trim().toLowerCase(Locale.ROOT);
        String displayName = StringUtils.hasText(identity.name()) ? identity.name() : username;
        // NO password, rather than a random one nobody holds. A stored hash makes the account look
        // password-enrolled: a tenant whose session policy lists PASSWORD among its re-auth factors would then
        // offer this user a password prompt they cannot possibly satisfy, locking them out of every step-up
        // gated action. Absent, the password factor is simply not enrolled — which is the truth.
        // SUPPRESS, for the reason the enum already gives for bulk import, only sharper: nobody asked for this
        // account. The address came from an upstream, so a challenge mailed at login time reaches a third party
        // who never initiated anything — and if the address was squatted, its real owner clicking that link
        // would stamp "verified" on somebody else's account. Verification is the holder's to start, after
        // login, through /api/auth/email-verification. KNOWN CONSEQUENCE: until they do, a tenant whose policy
        // requires the EMAIL factor cannot complete a first federated login.
        UserAccount created = users.createUser(
                new NewUser(username, username, displayName, null, Set.of(DEFAULT_ROLE)), orgId,
                OwnershipChallenge.SUPPRESS);
        organizations.addMember(orgId, created.getId());
        // Only when the upstream VERIFIED it. A SAML assertion merely carries an address the connection told us
        // to read; marking that verified would launder an unproven claim into a proof, and the verified flag is
        // exactly what the email-MATCHING branch trusts to attach a login to an account nobody connected.
        if (identity.emailVerified()) {
            users.markEmailVerified(created.getId());
        }
        return created;
    }

}
