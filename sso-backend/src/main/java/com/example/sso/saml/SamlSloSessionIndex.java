package com.example.sso.saml;

import java.util.Map;

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

    /** Drops the mapping once logout has been dispatched (idempotency: one send per termination). */
    void clear(String sid);
}
