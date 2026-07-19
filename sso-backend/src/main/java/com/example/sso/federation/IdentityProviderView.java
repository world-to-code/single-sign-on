package com.example.sso.federation;

/**
 * An upstream OIDC provider for the admin config surface — NEVER the client secret (write-only). {@code alias}
 * keys the login route; {@code scopes} is the space-separated OAuth scope list.
 */
public record IdentityProviderView(String alias, String displayName, String issuerUri, String clientId,
                                   String scopes, boolean allowJitProvisioning, boolean enabled) {
}
