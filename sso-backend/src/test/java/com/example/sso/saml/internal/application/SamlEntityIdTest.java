package com.example.sso.saml.internal.application;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link SamlEntityId}: the IdP entityID is derived from the request host, so each tenant
 * subdomain gets its own entityID (matching its own signing credential), and the bare host derives the
 * platform entityID.
 */
class SamlEntityIdTest {

    private final SamlEntityId entityId = new SamlEntityId();

    @Test
    void derivesTheEntityIdFromTheRequestHost() {
        assertThat(entityId.resolve(request("http", "acme.localhost", 9000)))
                .isEqualTo("http://acme.localhost:9000/saml2/idp");
        assertThat(entityId.resolve(request("http", "localhost", 9000)))
                .isEqualTo("http://localhost:9000/saml2/idp");
    }

    @Test
    void omitsTheSchemeDefaultPort() {
        assertThat(entityId.resolve(request("https", "acme.idp.example.com", 443)))
                .isEqualTo("https://acme.idp.example.com/saml2/idp");
        assertThat(entityId.resolve(request("http", "acme.localhost", 80)))
                .isEqualTo("http://acme.localhost/saml2/idp");
    }

    @Test
    void keepsANonDefaultPortForTheScheme() {
        assertThat(entityId.resolve(request("https", "acme.idp.example.com", 8443)))
                .isEqualTo("https://acme.idp.example.com:8443/saml2/idp");
        assertThat(entityId.resolve(request("http", "acme.localhost", 443)))
                .isEqualTo("http://acme.localhost:443/saml2/idp");
    }

    private MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort(port);
        return request;
    }
}
