package com.example.sso.federation.internal.application;

import java.util.List;

/**
 * A one-click OIDC provider preset for the admin console: a known vendor's issuer template, its default
 * scopes, and any extra fields the template needs. Data-defined — bound from {@code sso.federation.presets} —
 * so a new vendor is a config entry, not code. The console pre-fills the create form from it; the server still
 * re-validates the RESOLVED issuer (SSRF/https) on write, so a preset is a convenience, never a trust anchor.
 *
 * <p>{@code issuerTemplate} may carry {@code {key}} placeholders that the console substitutes from the
 * matching {@link FederationPresetField} inputs (e.g. {@code https://login.microsoftonline.com/{tenant}/v2.0}).
 * A preset with no fields is a fixed issuer.
 */
public record FederationPresetView(String id, String displayName, String issuerTemplate, String defaultScopes,
                                   List<FederationPresetField> fields) {

    public FederationPresetView {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
