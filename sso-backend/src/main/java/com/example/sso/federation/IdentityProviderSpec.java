package com.example.sso.federation;

/**
 * Validated write input for registering/updating an upstream OIDC provider. {@code clientSecret} is PLAINTEXT
 * on the write path (encrypted by the service before it touches the DB) and WRITE-ONLY — a blank value on an
 * update KEEPS the stored ciphertext. {@code alias} identifies the provider within the acting tier (the upsert
 * key); it is immutable once created.
 */
public record IdentityProviderSpec(String alias, String displayName, String issuerUri, String clientId,
                                   String clientSecret, String scopes, boolean allowJitProvisioning,
                                   boolean linkByVerifiedEmail, boolean enabled) {
}
