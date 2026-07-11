package com.example.sso.session.lifecycle;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Login-flow session lifecycle: registering the just-completed session with the concurrent-session
 * registry (enforcing the per-policy maximum) and rotating the session id on step-up. Consumed by the
 * authentication/step-up flow — distinct from the self-service {@link UserSessions} directory.
 */
public interface SessionLifecycle {

    /**
     * Registers the caller's current session, records its device metadata, and — if the user's resolved
     * session policy caps concurrency — expires the oldest overflow sessions (rejected on their next
     * request by the session-integrity filter).
     */
    void registerAndEnforceLimit(HttpServletRequest request, String username);

    /** Rotates the JSESSIONID, keeping the concurrent-session registry AND device metadata keyed by the new id. */
    void rotateSessionId(HttpServletRequest request, String username);
}
