package com.example.sso.saml.internal.core.application;

import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.organization.OrganizationView;
import com.example.sso.organization.CompanyProfile;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link SamlEntityId}: the IdP entityID is derived from the request host, so each tenant
 * subdomain gets its own entityID (matching its own signing credential), and the bare host derives the
 * platform entityID.
 */
class SamlEntityIdTest {

    private static final String PLATFORM_ENTITY_ID = "http://localhost:9000/saml2/idp";

    private final OrganizationService organizations = mock(OrganizationService.class);
    private final SamlEntityId entityId = new SamlEntityId(organizations, PLATFORM_ENTITY_ID);

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

    @Test
    void derivesATenantsEntityIdFromItsSlugWithoutARequest() {
        // The browser-less SLO path (back-channel LogoutRequest) has no Host header, but the message's Issuer
        // must still be the tenant's host-derived entityID — the same one its SSO assertions carry, and the
        // one the SP has registered. A platform entityID here is rejected by the SP as an Issuer mismatch.
        UUID orgId = UUID.randomUUID();
        when(organizations.findView(orgId)).thenReturn(Optional.of(org(orgId, "acme")));

        assertThat(entityId.forOrg(orgId)).isEqualTo("http://acme.localhost:9000/saml2/idp");
    }

    @Test
    void aGlobalRelyingPartyKeepsThePlatformEntityId() {
        assertThat(entityId.forOrg(null)).isEqualTo(PLATFORM_ENTITY_ID);
    }

    @Test
    void anUnresolvableOrgFallsBackToThePlatformEntityId() {
        UUID orgId = UUID.randomUUID();
        when(organizations.findView(orgId)).thenReturn(Optional.empty());

        assertThat(entityId.forOrg(orgId)).isEqualTo(PLATFORM_ENTITY_ID);
    }

    private OrganizationView org(UUID id, String slug) {
        return new OrganizationView(id, slug, slug, OrganizationStatus.ACTIVE, Instant.now(),
                CompanyProfile.empty(), false);
    }

    private MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort(port);
        return request;
    }
}
