package com.example.sso.federation.internal.application;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog's shaping, in isolation from config binding: it serves the configured presets in order, and the
 * null-defences that keep a missing list or a preset without fields from surfacing a null to the console.
 */
class FederationPresetCatalogTest {

    @Test
    void servesTheConfiguredPresetsInDeclarationOrder() {
        FederationPresetCatalog catalog = new FederationPresetCatalog(new FederationPresetProperties(List.of(
                new FederationPresetView("google", "Google", "https://accounts.google.com", "openid", List.of()),
                new FederationPresetView("okta", "Okta", "https://{domain}", "openid",
                        List.of(new FederationPresetField("domain", "Okta domain", "dev-1.okta.com"))))));

        assertThat(catalog.list()).extracting(FederationPresetView::id).containsExactly("google", "okta");
        assertThat(catalog.list().get(1).fields()).extracting(FederationPresetField::key).containsExactly("domain");
    }

    @Test
    void anAbsentPresetListBindsToEmptyNotNull() {
        FederationPresetCatalog catalog = new FederationPresetCatalog(new FederationPresetProperties(null));

        assertThat(catalog.list()).isEmpty();
    }

    @Test
    void aPresetWithoutFieldsExposesAnEmptyListNotNull() {
        FederationPresetView fixed = new FederationPresetView("google", "Google",
                "https://accounts.google.com", "openid", null);

        assertThat(fixed.fields()).isEmpty();
    }
}
