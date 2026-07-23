package com.example.sso.federation.internal.application;

/**
 * A standard OIDC profile claim a federated login can carry onto a user's attribute: the id_token claim
 * {@code name} (also the attribute key it maps to) and the {@code displayName} shown in the console.
 */
record FederationClaim(String name, String displayName) {
}
