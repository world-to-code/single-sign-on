package com.example.sso.session.policy;

import com.example.sso.metadata.AttributePredicate;
import java.util.Set;
import java.util.UUID;

/**
 * A session policy's assignment scope reconstructed from its {@code PORTAL/user} SESSION bindings: the users,
 * roles, and metadata predicates it governs. All three empty = an all-subjects binding (or none), i.e. the
 * policy applies to every user.
 */
public record SessionAssignment(Set<UUID> userIds, Set<UUID> roleIds, Set<AttributePredicate> attributes) {

    private static final SessionAssignment EMPTY = new SessionAssignment(Set.of(), Set.of(), Set.of());

    /** A scope with no predicate targets (users/roles only). */
    public SessionAssignment(Set<UUID> userIds, Set<UUID> roleIds) {
        this(userIds, roleIds, Set.of());
    }

    /** The scope of a policy with no per-subject binding — it applies to every user. */
    public static SessionAssignment empty() {
        return EMPTY;
    }
}
