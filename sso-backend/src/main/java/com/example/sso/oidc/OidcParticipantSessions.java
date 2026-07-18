package com.example.sso.oidc;

import java.util.List;
import java.util.Set;

/**
 * Read + single-participant logout over the OIDC back-channel session index, for the user portal's
 * "active app sessions" feature (goal ③: see the apps a session still holds, and log out ONE without
 * ending the session). Callers pass their OWN sessions' {@code sid}s; this never resolves another user's.
 */
public interface OidcParticipantSessions {

    /** The OIDC clients still holding a token under any of the given session {@code sid}s. */
    List<OidcParticipation> participationsFor(Set<String> sids);

    /**
     * Logs {@code username} out of ONE OIDC client (by its internal {@code registeredClientId}) for ONE
     * session: delivers a sid-scoped back-channel {@code logout_token} (the RP ends THAT session) and removes
     * the client from the session's participant index — leaving the IdP session and every other app untouched.
     * Best-effort delivery, always audited; a transiently-failed client is kept for the durable retry sweep.
     */
    void logout(String sid, String registeredClientId, String username);
}
