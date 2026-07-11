package com.example.sso.authpolicy.policy;

import com.example.sso.shared.IdName;
import com.example.sso.user.UserAccount;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read path of the authentication-policy engine: resolves the effective policy for a user
 * (highest-priority assigned policy, else the seeded default) and answers per-app policy queries.
 * Returns the public {@link AuthPolicyView}; the backing entity stays module-internal.
 */
public interface AuthPolicyResolver {

    String DEFAULT_NAME = "Default";

    AuthPolicyView resolveForUser(UserAccount user);

    AuthPolicyView defaultPolicy();

    /** The highest-priority ENABLED policy among the given ids, if any (per-app step-up resolution). */
    Optional<AuthPolicyView> highestPriorityEnabled(Collection<UUID> policyIds);

    /** Whether a policy with the given id exists (validation for app-policy assignment). */
    boolean exists(UUID policyId);

    /** (id, name) of every policy — for resolving policy display names without exposing the entity. */
    List<IdName> policyNames();
}
