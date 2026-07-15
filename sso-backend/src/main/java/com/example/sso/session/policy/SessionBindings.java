package com.example.sso.session.policy;

import com.example.sso.metadata.AttributePredicate;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which users/roles a session policy governs, stored in the {@code policy_binding} matrix as {@code PORTAL/user}
 * SESSION bindings. Declared here in the session module so its admin service can persist and read a policy's
 * assignment scope without importing the portal module (which owns {@code policy_binding}) — the implementation
 * lives in portal and is injected at runtime, avoiding a session&rarr;portal cycle.
 *
 * <p>Every session policy participates in per-user resolution (there is no "app-only" session policy), so a
 * policy's scope is either every user (an all-subjects binding, when both id sets are empty) or specific
 * users/roles (per-subject bindings). The matrix keeps exactly one binding per (app, subject, tenant), so a
 * subject (or the all-subjects slot) maps to a single session policy at a time; reassigning it moves the slot.
 */
public interface SessionBindings {

    /**
     * Make {@code policyId} govern the given scope, in the acting tier: bind every user when users, roles and
     * predicates are all empty, otherwise bind each user, role and metadata predicate. Slots this policy no
     * longer targets are cleared; a slot held by another policy is taken over. {@code priority} is the policy's
     * tie-break weight, stamped on each binding so two same-specificity bindings (e.g. a user in two roles)
     * resolve to the higher-priority policy.
     */
    void replaceForPolicy(UUID policyId, int priority, Set<UUID> userIds, Set<UUID> roleIds,
            Set<AttributePredicate> attributes);

    /** Assign with users/roles only (no metadata predicate targets). */
    default void replaceForPolicy(UUID policyId, int priority, Set<UUID> userIds, Set<UUID> roleIds) {
        replaceForPolicy(policyId, priority, userIds, roleIds, Set.of());
    }

    /** Remove every session binding referencing {@code policyId} in the acting tier (before the policy is deleted). */
    void clearForPolicy(UUID policyId);

    /** The assignment scope of each given policy, reconstructed from its bindings (RLS-scoped to the acting tier). */
    Map<UUID, SessionAssignment> describe(Collection<UUID> policyIds);
}
