package com.example.sso.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-tenant issuer derivation of {@link AdminElevationFilter}: the admin-console elevation token an
 * admin obtains at their TENANT subdomain carries the host-derived issuer ({@code http://acme.localhost:9000}),
 * so the elevation gate must expect THAT issuer — not a fixed platform issuer — or every tenant admin is
 * rejected (401) at their own subdomain. Deriving it from the request host also pins the token to this host,
 * refusing a token minted under another tenant's issuer.
 */
class AdminElevationFilterTest {

    private static final String PLATFORM_ISSUER = "http://localhost:9000";

    private MockHttpServletRequest request(String scheme, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        if (host != null) {
            request.addHeader("Host", host);
        }
        return request;
    }

    @Test
    void expectsTheTenantHostIssuerAtATenantSubdomain() {
        assertThat(AdminElevationFilter.expectedIssuer(request("http", "acme.localhost:9000"), PLATFORM_ISSUER))
                .isEqualTo("http://acme.localhost:9000");
    }

    @Test
    void expectsThePlatformIssuerAtTheBareHost() {
        assertThat(AdminElevationFilter.expectedIssuer(request("http", "localhost:9000"), PLATFORM_ISSUER))
                .isEqualTo(PLATFORM_ISSUER);
    }

    @Test
    void preservesSchemeAndPortFromTheRequest() {
        assertThat(AdminElevationFilter.expectedIssuer(request("https", "acme.idp.example.com"), PLATFORM_ISSUER))
                .isEqualTo("https://acme.idp.example.com");
    }

    @Test
    void fallsBackToTheConfiguredIssuerWhenHostIsAbsent() {
        assertThat(AdminElevationFilter.expectedIssuer(request("http", null), PLATFORM_ISSUER))
                .isEqualTo(PLATFORM_ISSUER);
    }
}
