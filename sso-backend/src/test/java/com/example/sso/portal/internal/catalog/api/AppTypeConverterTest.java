package com.example.sso.portal.internal.catalog.api;


import com.example.sso.portal.application.AppType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code {type}} path variable binds case-insensitively (so URLs stay lowercase), rejecting unknowns. */
class AppTypeConverterTest {

    private final AppTypeConverter converter = new AppTypeConverter();

    @Test
    void bindsAnyCaseAndTrims() {
        assertThat(converter.convert("oidc")).isEqualTo(AppType.OIDC);
        assertThat(converter.convert("SAML")).isEqualTo(AppType.SAML);
        assertThat(converter.convert(" Oidc ")).isEqualTo(AppType.OIDC);
    }

    @Test
    void rejectsUnknownValue() {
        assertThatThrownBy(() -> converter.convert("ldap")).isInstanceOf(IllegalArgumentException.class);
    }
}
