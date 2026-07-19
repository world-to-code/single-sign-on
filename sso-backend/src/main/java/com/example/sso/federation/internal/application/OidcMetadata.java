package com.example.sso.federation.internal.application;

/**
 * The subset of an upstream's OIDC discovery document the login flow needs: the {@code issuer} (must equal the
 * configured one), and the authorization / token / JWKS endpoints. Every host here is SSRF-validated before use.
 */
record OidcMetadata(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri) {
}
