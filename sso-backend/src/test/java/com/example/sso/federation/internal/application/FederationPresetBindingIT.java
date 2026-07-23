package com.example.sso.federation.internal.application;

import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The presets actually bind from {@code application.yml} — a relaxed-binding property name (kebab vs camel) or
 * a mistyped key would otherwise leave the catalog silently empty and the console with no cards, with nothing
 * failing. Pins the shipped Google/Entra/Okta entries, including that Entra's issuer template carries its
 * {@code {tenant}} placeholder and field.
 */
class FederationPresetBindingIT extends AbstractIntegrationTest {

    @Autowired FederationPresetCatalog catalog;

    @Test
    void theShippedPresetsBindFromConfig() {
        assertThat(catalog.list()).extracting(FederationPresetView::id)
                .contains("google", "entra", "okta");

        FederationPresetView entra = catalog.list().stream()
                .filter(preset -> preset.id().equals("entra")).findFirst().orElseThrow();
        assertThat(entra.issuerTemplate()).contains("{tenant}");
        assertThat(entra.defaultScopes()).isEqualTo("openid email profile");
        assertThat(entra.fields()).extracting(FederationPresetField::key).containsExactly("tenant");
    }
}
