package com.example.sso.auth.internal.login.application;

import com.example.sso.federation.FederatedIdentity;
import com.example.sso.organization.OrganizationService;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Just-in-time provisioning of a federated user, as ONE transaction: create the account, make it a member of
 * the tenant, and mark its email verified (the upstream proved the address). Atomicity matters — if these were
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
        String username = identity.email(); // unique within the org, and a stable handle
        String displayName = StringUtils.hasText(identity.name()) ? identity.name() : identity.email();
        // NO password, rather than a random one nobody holds. A stored hash makes the account look
        // password-enrolled: a tenant whose session policy lists PASSWORD among its re-auth factors would then
        // offer this user a password prompt they cannot possibly satisfy, locking them out of every step-up
        // gated action. Absent, the password factor is simply not enrolled — which is the truth.
        UserAccount created = users.createUser(
                new NewUser(username, identity.email(), displayName, null, Set.of(DEFAULT_ROLE)), orgId);
        organizations.addMember(orgId, created.getId());
        users.markEmailVerified(created.getId());
        return created;
    }

}
