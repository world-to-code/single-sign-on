package com.example.sso.auth;

import com.example.sso.auth.internal.login.application.FederatedAuthenticationService;
import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security chain in front of the inbound assertion consumer — the one place in this product where a
 * state-changing POST is deliberately CSRF-exempt.
 *
 * <p>Nothing else can cover this. {@code FederationControllerTest} is a standalone MockMvc setup with no
 * filter chain, so it would pass with the exemption deleted AND with CSRF disabled everywhere; only the real
 * chain can tell those two apart. The exemption is what makes the feature work at all: the upstream IdP makes
 * the browser POST here cross-site, {@code SameSite=Lax} means no session cookie rides along, and there is no
 * page of ours in between to have handed out a token.
 *
 * <p>So both directions are asserted. A missing exemption is a feature that never works; an over-broad one
 * (say {@code /api/auth/federation/**}) is a hole in every other route it swallows, and the widening would be
 * invisible to a test that only checked the ACS.
 */
@AutoConfigureMockMvc
class FederationAcsSecurityChainIT extends AbstractIntegrationTest {

    private static final String ACS_URI = "/api/auth/federation/corp/acs";

    @Autowired
    MockMvc mvc;
    /**
     * Mocked to do nothing: what is under test is whether the request REACHES the handler. A real call would
     * refuse an unconfigured alias, and the controller renders that as the same redirect as success — which
     * would make the assertion below true for the wrong reason.
     */
    @MockitoBean
    FederatedAuthenticationService federatedAuth;

    @Test
    void theAcsAcceptsACrossSitePostCarryingNoCsrfToken() throws Exception {
        // The whole feature rests on this: no token can exist on a POST the upstream IdP generated.
        mvc.perform(post(ACS_URI)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("SAMLResponse", "irrelevant")
                        .param("RelayState", "irrelevant"))
                .andExpect(status().is3xxRedirection());

        verify(federatedAuth).completeSaml(any(), any(), any(), any(), any());
    }

    @Test
    void anotherPostUnderTheSamePrefixIsStillCsrfProtected() throws Exception {
        // The negative control. Without it, disabling CSRF wholesale — or widening the matcher to the
        // federation prefix — would leave the test above green and this product open.
        mvc.perform(post("/api/auth/email-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAcsIsReachableWithoutASession() throws Exception {
        // permitAll, not merely CSRF-exempt: the browser arrives here with no cookie of ours at all, so an
        // authentication requirement would refuse every federated login before the assertion is ever read.
        mvc.perform(post(ACS_URI)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("SAMLResponse", "irrelevant"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void anAcsPostWithNoAssertionNeverReachesTheLoginPath() throws Exception {
        // Being exempt from CSRF does not make the endpoint a free call into the login path. It still renders
        // as a redirect — an SPA failure page, not an error body — so only the mock can show the difference.
        mvc.perform(post(ACS_URI).contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().is3xxRedirection());

        verify(federatedAuth, never()).completeSaml(any(), any(), any(), any(), any());
    }
}
