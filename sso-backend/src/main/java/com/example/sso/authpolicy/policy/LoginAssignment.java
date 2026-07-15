package com.example.sso.authpolicy.policy;

import com.example.sso.metadata.AttributePredicate;
import java.util.Set;
import java.util.UUID;

/**
 * A policy's login scope reconstructed from its {@code PORTAL/user} auth bindings: whether it governs login
 * at all, and the users/roles/metadata predicates it targets ({@code appliesToLogin} true with all sets empty
 * = every user).
 */
public record LoginAssignment(boolean appliesToLogin, Set<UUID> userIds, Set<UUID> roleIds,
                              Set<AttributePredicate> attributes) {

    private static final LoginAssignment NONE = new LoginAssignment(false, Set.of(), Set.of(), Set.of());

    /** A login scope with no predicate targets (users/roles only). */
    public LoginAssignment(boolean appliesToLogin, Set<UUID> userIds, Set<UUID> roleIds) {
        this(appliesToLogin, userIds, roleIds, Set.of());
    }

    /** The scope of a policy with no login binding — not used for login. */
    public static LoginAssignment none() {
        return NONE;
    }
}
