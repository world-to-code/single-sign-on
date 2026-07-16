package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.session.lifecycle.UserSessions;
import com.example.sso.user.account.UserService;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link SessionTerminationRequest} into an actual termination, from persisted data alone — so both the
 * immediate (in-thread) attempt and a later durable sweep re-drive run the SAME logic without a captured
 * closure that a restart would lose. Both steps run correctly on a browser-less scheduler thread with no
 * security or tenant context bound: the username is resolved through {@link UserService#usernameOf} (an
 * RLS-bypassing lookup, robust even if {@code app_user} later gains RLS), and termination only touches Redis.
 */
@Component
class SessionTerminationRedriver {

    private final UserSessions sessions;
    private final UserService users;

    SessionTerminationRedriver(UserSessions sessions, UserService users) {
        this.sessions = sessions;
        this.users = users;
    }

    /**
     * Terminate the request's target and return how many sessions were dropped. A member request whose user no
     * longer resolves (deleted before the re-drive) returns 0 — nothing to terminate — rather than failing.
     */
    int redrive(SessionTerminationRequest request) {
        String username = request.username() != null ? request.username()
                : users.usernameOf(request.userId()).orElse(null);
        return username == null ? 0 : sessions.terminateForUser(username, request.orgId());
    }
}
