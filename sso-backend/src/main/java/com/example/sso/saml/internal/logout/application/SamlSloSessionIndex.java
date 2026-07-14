package com.example.sso.saml.internal.logout.application;

import java.util.Map;
import java.util.Set;

/**
 * Records which SAML SPs an OP session did SSO to (keyed by the OP {@code sid}, shared with OIDC), so that
 * on session termination we know whom to send a {@code LogoutRequest} and with which NameID. Kept in Redis
 * (shared across nodes) with a TTL covering the longest session — mirrors the OIDC participant index. The
 * NameID format is not stored: it is re-read from the relying party at logout time.
 */
public interface SamlSloSessionIndex {

    /** Records that {@code entityId} received an assertion for this session with the given NameID value. */
    void record(String sid, String entityId, String nameId);

    /** entityId → NameID for every SP that participated in the session, or empty if none/expired. */
    Map<String, String> lookup(String sid);

    /**
     * Removes the {@code entityIds} whose logout is settled (delivered, or terminally non-deliverable — RP
     * gone, no SLO endpoint, or a front-channel binding the browser-less path can never reach) and returns
     * how many SPs still remain to retry. Enables clear-only-delivered: a transiently-failed SOAP SP stays
     * for the durable retry sweep instead of being lost.
     */
    int removeParticipants(String sid, Set<String> entityIds);

    /** Drops the mapping once logout has been dispatched (idempotency: one send per termination). */
    void clear(String sid);
}
