package com.example.sso.authpolicy.policy;

import com.example.sso.metadata.AttributePredicate;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The login-time sign-on policy of an auth policy, stored in the {@code policy_binding} matrix as
 * {@code PORTAL/user} auth bindings. Declared here in the authpolicy module so its admin service can persist
 * and read a policy's login scope without importing the portal module (which owns {@code policy_binding}) —
 * the implementation lives in portal and is injected at runtime, avoiding an authpolicy&rarr;portal cycle.
 *
 * <p>A policy's login scope is one of: not used for login (no binding), used for every user (an all-subjects
 * binding), or used for specific users/roles (per-subject bindings). Because the matrix keeps exactly one
 * binding per (app, subject, tenant), a subject (or the all-subjects slot) maps to a single login policy at a
 * time; reassigning it moves that slot to the new policy.
 */
public interface LoginAuthBindings {

    /**
     * Make {@code policyId} the login policy for the given scope, in the acting tier: clear it when
     * {@code appliesToLogin} is false; bind every user when both id sets are empty; otherwise bind each user
     * and role. Slots this policy no longer targets are cleared; a slot held by another policy is taken over.
     * {@code priority} is the policy's tie-break weight, stamped on each binding so two same-specificity login
     * bindings (e.g. a user in two roles) resolve to the higher-priority policy, as the pre-matrix engine did.
     */
    void replaceForPolicy(UUID policyId, int priority, boolean appliesToLogin, Set<UUID> userIds, Set<UUID> roleIds,
            Set<AttributePredicate> attributes);

    /** Assign login with users/roles only (no metadata predicate targets). */
    default void replaceForPolicy(UUID policyId, int priority, boolean appliesToLogin, Set<UUID> userIds,
            Set<UUID> roleIds) {
        replaceForPolicy(policyId, priority, appliesToLogin, userIds, roleIds, Set.of());
    }

    /** Remove every login binding referencing {@code policyId} in the acting tier (before the policy is deleted). */
    void clearForPolicy(UUID policyId);

    /** The login scope of each given policy, reconstructed from its bindings (RLS-scoped to the acting tier). */
    Map<UUID, LoginAssignment> describe(Collection<UUID> policyIds);
}
