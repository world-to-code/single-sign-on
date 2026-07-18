package com.example.sso.oidc;

import java.util.Set;

/**
 * Records which OIDC clients received a token under a given OP session (keyed by the {@code sid}), so that
 * when the session terminates we know whom to send a back-channel {@code logout_token}. The {@code /oauth2/
 * token} exchange has no HTTP session, so this mapping is captured at token-issue time and kept in Redis
 * (shared, so the termination listener can read it on any node) with a TTL covering the longest session.
 *
 * <p>Clients are identified by their <b>globally-unique internal id</b> ({@code RegisteredClient.getId()}),
 * NOT the {@code client_id}: a {@code client_id} is unique only per tenant (two orgs may register the same
 * one), so resolving the owning org from a bare {@code client_id} at browser-less logout time is ambiguous
 * and could sign/deliver a logout to the WRONG tenant. The internal id resolves the org unambiguously.
 */
public interface OidcBackchannelSessionIndex {

    /** Records that {@code registeredClientId} was issued a token for session {@code sid} owned by {@code username}. */
    void record(String sid, String registeredClientId, String username);

    /** The clients (by internal id) and subject that participated in the session, or an empty record if none. */
    Participants lookup(String sid);

    /**
     * Removes the {@code registeredClientIds} whose logout is settled (delivered, or terminally non-deliverable)
     * and returns how many clients still remain to retry. The subject is retained while any client remains (a
     * give-up audit needs it) and dropped with the last one. Enables clear-only-delivered: a transiently failed
     * client stays for the durable retry sweep instead of being lost.
     */
    int removeParticipants(String sid, Set<String> registeredClientIds);

    /** Drops the mapping once its logout has been dispatched (idempotency: one send per termination). */
    void clear(String sid);

    record Participants(String username, Set<String> registeredClientIds) {
    }
}
