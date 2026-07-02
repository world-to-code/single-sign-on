package com.example.sso.session;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * The self-service "My sessions" directory: listing the signed-in user's live sessions and revoking
 * one by its opaque public handle. Distinct from the login-flow {@link SessionLifecycle}.
 */
public interface UserSessions {

    /**
     * The user's live sessions (registry-backed liveness), backfilling the caller's own current session
     * into the registry + metadata store when missing (e.g. after an in-memory restart).
     */
    List<SessionMetadata> liveSessions(HttpServletRequest request, String username);

    /**
     * Revokes one of the user's OWN sessions by its opaque public handle; scoped to the user so another
     * user's session is never reachable. Throws {@code NotFoundException} when no such handle exists.
     */
    void revoke(String username, String handle);
}
