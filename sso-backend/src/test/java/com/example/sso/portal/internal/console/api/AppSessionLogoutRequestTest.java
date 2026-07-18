package com.example.sso.portal.internal.console.api;

import com.example.sso.portal.application.AppType;
import com.example.sso.shared.error.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The request DTO must map a known app-type string to its {@link AppType} and reject an unknown one as a 400
 * (a bare {@code IllegalArgumentException} would leak as a 500).
 */
class AppSessionLogoutRequestTest {

    @Test
    void knownTypesMapToTheEnum() {
        assertThat(new AppSessionLogoutRequest("OIDC", "c-1").appType()).isEqualTo(AppType.OIDC);
        assertThat(new AppSessionLogoutRequest("SAML", "sp-1").appType()).isEqualTo(AppType.SAML);
    }

    @Test
    void anUnknownTypeIsARejectedRequestNotAServerError() {
        assertThatThrownBy(() -> new AppSessionLogoutRequest("GARBAGE", "x").appType())
                .isInstanceOf(BadRequestException.class);
    }
}
