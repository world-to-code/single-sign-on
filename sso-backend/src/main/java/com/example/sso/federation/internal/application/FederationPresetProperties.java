package com.example.sso.federation.internal.application;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The catalog of one-click provider presets, bound from {@code sso.federation.presets}. Platform-wide vendor
 * knowledge (issuer templates, default scopes) — the same for every tenant — so it lives in configuration
 * rather than the database. An absent list binds to empty rather than null, so a deployment that ships no
 * presets simply offers no cards (the custom-OIDC form still works).
 */
@ConfigurationProperties("sso.federation")
public record FederationPresetProperties(List<FederationPresetView> presets) {

    public FederationPresetProperties {
        presets = presets == null ? List.of() : List.copyOf(presets);
    }
}
