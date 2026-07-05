package com.example.sso.saml;

import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The IdP SAML metadata is host-scoped: its entityID is derived from the request host, so each tenant
 * subdomain publishes its own IdP identity (matching its own signing credential), the bare platform host
 * derives the platform entityID, and an unrecognised host is refused.
 */
@AutoConfigureMockMvc
class SamlMetadataIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theBarePlatformHostPublishesThePlatformEntityId() throws Exception {
        String xml = mvc.perform(get("http://localhost:9000/saml2/idp/metadata"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(xml).contains("entityID=\"http://localhost:9000/saml2/idp\"");
    }

    @Test
    void aTenantSubdomainPublishesItsOwnEntityId() throws Exception {
        String xml = mvc.perform(get("http://" + DEFAULT_ORG_SLUG + ".localhost:9000/saml2/idp/metadata"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(xml).contains("entityID=\"http://" + DEFAULT_ORG_SLUG + ".localhost:9000/saml2/idp\"");
        // Endpoints follow the same host.
        assertThat(xml).contains("http://" + DEFAULT_ORG_SLUG + ".localhost:9000/saml2/idp/sso");
    }

    @Test
    void anUnrecognisedHostIsRefused() throws Exception {
        // The host derives the entityID, so an arbitrary host must not publish a forged one.
        mvc.perform(get("http://evil.com/saml2/idp/metadata"))
                .andExpect(status().isNotFound());
    }
}
