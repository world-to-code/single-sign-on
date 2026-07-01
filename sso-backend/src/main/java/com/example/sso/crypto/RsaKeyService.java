package com.example.sso.crypto;

import com.nimbusds.jose.jwk.JWKSet;

/**
 * Crypto module's public contract for RSA signing keys: builds the {@link JWKSet} published by the
 * OAuth2 Authorization Server and rotates the active key. The backing {@code SigningKey} entity and
 * the implementation stay module-internal.
 */
public interface RsaKeyService {

    /**
     * Builds the JWK set used by the authorization server. Only the active key is exposed so the JWT
     * encoder can unambiguously select a signing key; rotation swaps which key is active (relying
     * parties re-fetch the JWKS to pick up the new key).
     */
    JWKSet buildJwkSet();

    /**
     * Rotates the signing key: the current active key is deactivated (kept published for verifying
     * already-issued tokens) and a new active key is generated to sign new ones. Returns the new
     * active key id.
     */
    String rotate();
}
