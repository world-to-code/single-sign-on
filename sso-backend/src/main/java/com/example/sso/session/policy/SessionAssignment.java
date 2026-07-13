package com.example.sso.session.policy;

import java.util.Set;
import java.util.UUID;

/**
 * A session policy's assignment scope reconstructed from its {@code PORTAL/user} SESSION bindings: the users and
 * roles it governs. Both sets empty = an all-subjects binding (or none), i.e. the policy applies to every user.
 */
public record SessionAssignment(Set<UUID> userIds, Set<UUID> roleIds) {

    private static final SessionAssignment EMPTY = new SessionAssignment(Set.of(), Set.of());

    /** The scope of a policy with no per-subject binding — it applies to every user. */
    public static SessionAssignment empty() {
        return EMPTY;
    }
}
