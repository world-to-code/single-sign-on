package com.example.sso.crypto;

import com.nimbusds.jose.jwk.JWKSet;

/**
 * Crypto module's public contract for RSA signing keys: builds the {@link JWKSet} published by the
 * OAuth2 Authorization Server and rotates the active key. The backing {@code SigningKey} entity and
 * the implementation stay module-internal.
 */
public interface RsaKeyService {

    /**
     * Builds the JWK set used by the authorization server for the acting tier: the ACTIVE key first (the
     * JWT encoder signs with the first match), followed by up to {@link #retainedInactiveKeys()} rotated-away
     * keys kept published so tokens they signed stay verifiable until they expire.
     */
    JWKSet buildJwkSet();

    /**
     * Rotates the signing key: the current active key is deactivated (kept published for verifying
     * already-issued tokens) and a new active key is generated to sign new ones. Returns the new
     * active key id.
     */
    String rotate();

    /**
     * The acting tier's JWKS retention: how many rotated-away keys stay published. Resolves the tier's own
     * setting, falling back to the global default row, then the configured default.
     */
    int retainedInactiveKeys();

    /**
     * Sets the acting tier's JWKS retention (copy-on-write: a tenant's first save creates its own row).
     * With no bound org only the platform context may write — the global default is inherited by every
     * tenant that has not customized its own. Returns the stored value.
     */
    int updateRetainedInactiveKeys(int retainedInactiveKeys);
}
