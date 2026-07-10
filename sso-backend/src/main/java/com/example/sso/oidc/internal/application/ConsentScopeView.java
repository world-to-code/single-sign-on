package com.example.sso.oidc.internal.application;

/**
 * A single requested OIDC scope paired with a human-readable, already-localized description, for
 * display on the consent screen. The raw scope name is retained so the reader can see exactly what is
 * granted. Descriptions are resolved by {@link ConsentModelService} against the message bundle
 * ({@code consent.scope.*}) in the request locale, so no copy is hard-coded here.
 */
public record ConsentScopeView(String scope, String description) {
}
