package com.example.sso.scim;

import java.time.Duration;
import java.util.Optional;

/**
 * SCIM module's public contract for issuing and authenticating SCIM bearer tokens. The plaintext token
 * is returned only at issuance; only its SHA-256 hash is stored. The implementation stays
 * module-internal.
 */
public interface ScimTokenService {

    /**
     * Issues a new token OWNED BY the acting tenant (the bound org, or global when none is bound) and
     * returns its plaintext value (store it now — it is not recoverable).
     */
    String issue(String description, Duration ttl);

    /** Ensures a specific global token exists (used to seed a stable dev token). Idempotent. */
    void ensureToken(String rawToken, String description);

    /**
     * Authenticates a raw token: returns the token's tenant (a {@link ScimPrincipal}, org possibly null for
     * a global token) if it is valid and active, else empty. The token lookup runs cross-org (the request
     * is not yet tenant-bound), and the caller binds the returned org for the SCIM request.
     */
    Optional<ScimPrincipal> authenticate(String rawToken);
}
