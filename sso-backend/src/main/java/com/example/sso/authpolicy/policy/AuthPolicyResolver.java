package com.example.sso.authpolicy.policy;

import com.example.sso.shared.IdName;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read path of the authentication-policy engine: resolves the seeded default policy and answers per-app
 * policy queries. Login-scope resolution now runs off the {@code policy_binding} matrix (PORTAL/user auth
 * bindings), not off assignments held on the policy. Returns the public {@link AuthPolicyView}; the backing
 * entity stays module-internal.
 */
public interface AuthPolicyResolver {

    String DEFAULT_NAME = "Default";

    AuthPolicyView defaultPolicy();

    /** The highest-priority ENABLED policy among the given ids, if any (per-app step-up resolution). */
    Optional<AuthPolicyView> highestPriorityEnabled(Collection<UUID> policyIds);

    /** Whether a policy with the given id exists (validation for app-policy assignment). */
    boolean exists(UUID policyId);

    /** (id, name) of every policy — for resolving policy display names without exposing the entity. */
    List<IdName> policyNames();
}
