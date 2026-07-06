package com.example.sso.oidc.internal.application;

import org.springframework.security.oauth2.core.oidc.OidcScopes;

import java.util.Map;

/**
 * A single requested OIDC scope paired with a human-readable description, for display on the
 * consent screen. The raw scope name is retained so the reader can see exactly what is granted.
 */
public record ConsentScopeView(String scope, String description) {

    /** Friendly descriptions for the standard OIDC scopes; unknown scopes fall back to their name. */
    private static final Map<String, String> DESCRIPTIONS = Map.of(
            OidcScopes.PROFILE, "Your basic profile — display name and username",
            OidcScopes.EMAIL, "Your email address",
            OidcScopes.ADDRESS, "Your postal address",
            OidcScopes.PHONE, "Your phone number");

    public static ConsentScopeView of(String scope) {
        String description = DESCRIPTIONS.getOrDefault(scope, scope.replace('_', ' ').replace('.', ' '));
        return new ConsentScopeView(scope, description);
    }
}
