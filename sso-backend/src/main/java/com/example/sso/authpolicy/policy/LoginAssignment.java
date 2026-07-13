package com.example.sso.authpolicy.policy;

import java.util.Set;
import java.util.UUID;

/**
 * A policy's login scope reconstructed from its {@code PORTAL/user} auth bindings: whether it governs login
 * at all, and the users/roles it targets ({@code appliesToLogin} true with both sets empty = every user).
 */
public record LoginAssignment(boolean appliesToLogin, Set<UUID> userIds, Set<UUID> roleIds) {

    private static final LoginAssignment NONE = new LoginAssignment(false, Set.of(), Set.of());

    /** The scope of a policy with no login binding — not used for login. */
    public static LoginAssignment none() {
        return NONE;
    }
}
