package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.application.AppType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the {@link AppKey} composite ({@code type:id}) used to index apps across protocols. */
class AppKeyTest {

    @Test
    void composesTypeAndId() {
        assertThat(AppKey.of(AppType.OIDC, "my-app")).isEqualTo("OIDC:my-app");
    }

    @Test
    void theSameIdUnderDifferentTypesIsDistinct() {
        assertThat(AppKey.of(AppType.OIDC, "x")).isNotEqualTo(AppKey.of(AppType.SAML, "x"));
    }
}
