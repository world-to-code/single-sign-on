package com.example.sso.oidc;

import java.util.Set;

/**
 * Records which OIDC clients received a token under a given OP session (keyed by the {@code sid}), so that
 * when the session terminates we know whom to send a back-channel {@code logout_token}. The {@code /oauth2/
 * token} exchange has no HTTP session, so this mapping is captured at token-issue time and kept in Redis
 * (shared, so the termination listener can read it on any node) with a TTL covering the longest session.
 */
public interface OidcBackchannelSessionIndex {

    /** Records that {@code clientId} was issued a token for the session {@code sid} owned by {@code username}. */
    void record(String sid, String clientId, String username);

    /** The clients (and subject) that participated in the session, or an empty record if none/expired. */
    Participants lookup(String sid);

    /**
     * Removes the {@code clientIds} whose logout is settled (delivered, or terminally non-deliverable) and
     * returns how many clients still remain to retry. The subject is retained while any client remains (a
     * give-up audit needs it) and dropped with the last one. Enables clear-only-delivered: a transiently
     * failed client stays for the durable retry sweep instead of being lost.
     */
    int removeParticipants(String sid, Set<String> clientIds);

    /** Drops the mapping once its logout has been dispatched (idempotency: one send per termination). */
    void clear(String sid);

    record Participants(String username, Set<String> clientIds) {
    }
}
