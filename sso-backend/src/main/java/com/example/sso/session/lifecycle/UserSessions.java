package com.example.sso.session.lifecycle;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    /**
     * Terminates a user's live sessions bound to their organization {@code orgId} (admin force-expiry, or
     * automatically when the user is disabled/deleted/re-roled). A {@code null} orgId is a global/platform
     * account, matching only sessions that carry NO org marker. Usernames are unique only within an org, so
     * scoping by org is what stops a same-named user in ANOTHER tenant from being logged out too. Deleting the
     * Redis session fires the termination listeners (OIDC back-channel logout / SAML SLO). Returns the count.
     */
    int terminateForUser(String username, UUID orgId);

    /**
     * The ids of the user's live sessions bound to {@code orgId} (or, for a {@code null} orgId, those carrying
     * no org marker) — so an admin session view can be filtered to the tenant's own user, never a same-named
     * user in another org.
     */
    Set<String> sessionIdsForUser(String username, UUID orgId);
}
