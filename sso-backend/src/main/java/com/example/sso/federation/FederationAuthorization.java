package com.example.sso.federation;

/**
 * The start of a federated login: where to send the browser ({@code authorizationUri}) and the one-time values
 * the caller must stash server-side (never in the URL alone) to validate the callback — {@code state} (CSRF /
 * request binding), {@code nonce} (id_token replay binding), and the PKCE {@code codeVerifier} (proves the
 * token exchange comes from the same client that started the flow).
 */
public record FederationAuthorization(String authorizationUri, String state, String nonce, String codeVerifier) {
}
