package com.example.sso.federation;

/**
 * A tenant's enabled upstream OIDC provider as offered on the login screen — just the {@code alias} (the login
 * route segment) and the button label. Carries no configuration or secret.
 */
public record FederationProvider(String alias, String displayName) {
}
