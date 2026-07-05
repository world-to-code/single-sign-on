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
        assertThat(entityId.resolve(request("acme.localhost", 9000)))
                .isEqualTo("http://acme.localhost:9000/saml2/idp");
        assertThat(entityId.resolve(request("localhost", 9000)))
                .isEqualTo("http://localhost:9000/saml2/idp");
    }

    private MockHttpServletRequest request(String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName(host);
        request.setServerPort(port);
        return request;
    }
}
