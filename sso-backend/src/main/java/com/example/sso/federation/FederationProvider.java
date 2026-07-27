package com.example.sso.federation;

/**
 * A provider offered on the sign-in screen. Carries the protocol because the two start in different ways — an
 * OIDC redirect the server builds from discovery, a SAML AuthnRequest it signs — and because the console shows
 * which kind a connection is. No configuration and no secret: this is what an unauthenticated visitor may see.
 */
public record FederationProvider(String alias, String displayName, FederationProtocol protocol) {
}
