package com.example.sso.saml.internal.inbound.application;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SP identity a tenant federates under. Both values are compared as STRINGS by the upstream IdP — an
 * assertion is addressed to an audience and posted to a recipient — so the derivation has to be byte-stable:
 * the tenant's own host, no default port, and distinct per connection.
 */
class SamlSpIdentityImplTest {

    private final SamlSpIdentityImpl identity = new SamlSpIdentityImpl();

    private MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort(port);
        request.setRequestURI("/api/admin/identity-providers/corp/saml/metadata");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    @Test
    void theEntityIdIsDerivedFromTheTenantsOwnHost() {
        // A tenant on its own subdomain federates under its own SP identity — the same host-derived rule the
        // IdP-role entityID follows, so no configured base URL can drift from the origin actually in use.
        assertThat(identity.entityId(request("https", "acme.idp.example", 443), "corp"))
                .isEqualTo("https://acme.idp.example/saml2/sp/corp");
        assertThat(identity.entityId(request("https", "other.idp.example", 443), "corp"))
                .isEqualTo("https://other.idp.example/saml2/sp/corp");
    }

    @Test
    void aDefaultPortIsOmittedButANonDefaultOneIsKept() {
        // A stray ":443" would make the entityID a different string to the upstream and break the connection.
        assertThat(identity.entityId(request("https", "acme.idp.example", 443), "corp"))
                .isEqualTo("https://acme.idp.example/saml2/sp/corp");
        assertThat(identity.entityId(request("http", "localhost", 9000), "corp"))
                .isEqualTo("http://localhost:9000/saml2/sp/corp");
    }

    @Test
    void eachConnectionGetsItsOwnIdentityAndConsumerUrl() {
        // One tenant may federate to several upstreams; sharing an SP identity would let an assertion minted
        // for one connection be replayed at another.
        assertThat(identity.entityId(request("https", "acme.idp.example", 443), "corp"))
                .isNotEqualTo(identity.entityId(request("https", "acme.idp.example", 443), "partner"));
        assertThat(identity.acsUrl(request("https", "acme.idp.example", 443), "corp"))
                .isNotEqualTo(identity.acsUrl(request("https", "acme.idp.example", 443), "partner"));
    }

    @Test
    void theConsumerUrlSitsUnderTheRateLimitedFederationPrefix() {
        // The ACS is unauthenticated and can create an account, so it must land under /api/auth/federation/,
        // which AuthRateLimitFilter covers method-agnostically. Moving it elsewhere silently drops that limit.
        assertThat(identity.acsUrl(request("https", "acme.idp.example", 443), "corp"))
                .isEqualTo("https://acme.idp.example/api/auth/federation/corp/acs")
                .startsWith("https://acme.idp.example/api/auth/federation/");
    }
}
