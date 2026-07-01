package com.example.sso.scim;

import java.time.Duration;

/**
 * SCIM module's public contract for issuing and validating SCIM bearer tokens. The plaintext token
 * is returned only at issuance; only its SHA-256 hash is stored. The implementation stays
 * module-internal.
 */
public interface ScimTokenService {

    /** Issues a new token and returns its plaintext value (store it now — it is not recoverable). */
    String issue(String description, Duration ttl);

    /** Ensures a specific token exists (used to seed a stable dev token). Idempotent. */
    void ensureToken(String rawToken, String description);

    boolean isValid(String rawToken);
}
