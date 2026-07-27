package com.example.sso.auth.internal.login.api;

import com.example.sso.auth.internal.login.application.FederatedAuthenticationService;
import com.example.sso.shared.error.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the federation browser endpoints: {@code /start} 302s to the upstream authorization URI; the
 * OIDC callback and the SAML assertion consumer both establish the session and 302 to {@code /} on success, and
 * to {@code /?login_error=federation} on an upstream error, a missing code or assertion, or a validation failure
 * ({@code ApiException}) — never a raw JSON error in the address bar, and never an account-existence oracle.
 *
 * <p>A standalone MVC setup, so there is no filter chain here: what the ACS's {@code permitAll} and CSRF
 * exemption actually do is pinned against the real chain in {@code FederationAcsSecurityChainIT}.
 */
class FederationControllerTest {

    private static final String ACS_URI = "/api/auth/federation/corp/acs";
    private static final String FAILURE_REDIRECT = "/?login_error=federation";

    private final FederatedAuthenticationService service = mock(FederatedAuthenticationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FederationController(service)).build();
    }

    @Test
    void startRedirectsToTheUpstreamAuthorizationUri() throws Exception {
        when(service.start(eq("google"), any(), any())).thenReturn("https://accounts.example.com/authorize?x=1");

        mvc.perform(get("/api/auth/federation/google/start"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://accounts.example.com/authorize?x=1"));
    }

    @Test
    void aSuccessfulCallbackEstablishesTheSessionAndRedirectsHome() throws Exception {
        mvc.perform(get("/api/auth/federation/google/callback").param("code", "c").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));

        verify(service).complete(eq("google"), eq("c"), eq("s"), any(), any());
    }

    @Test
    void anUpstreamErrorRedirectsToFailureWithoutCompleting() throws Exception {
        mvc.perform(get("/api/auth/federation/google/callback").param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(FAILURE_REDIRECT));

        verify(service, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void aMissingCodeRedirectsToFailureWithoutCompleting() throws Exception {
        mvc.perform(get("/api/auth/federation/google/callback").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(FAILURE_REDIRECT));

        verify(service, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void aValidationFailureRedirectsToFailureRatherThanLeakingAJsonError() throws Exception {
        doThrow(new UnauthorizedException()).when(service).complete(eq("google"), any(), any(), any(), any());

        mvc.perform(get("/api/auth/federation/google/callback").param("code", "bad").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(FAILURE_REDIRECT));
    }

    /**
     * An UNEXPECTED failure — a store error, an oversized upstream claim — must render the same non-revealing
     * redirect as a rejection, not a stack-trace page. The catch was widened past ApiException for exactly
     * this, and narrowing it back would surface a 500 to the browser on an unauthenticated route.
     */
    @Test
    void anUnexpectedFailureStillRendersTheOrdinaryFailureRedirect() throws Exception {
        doThrow(new IllegalStateException("boom")).when(service).complete(any(), any(), any(), any(), any());

        mvc.perform(get("/api/auth/federation/google/callback").param("code", "code-1").param("state", "state-1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(FAILURE_REDIRECT));
    }

    // --- the assertion consumer service -------------------------------------------------------------

    @Test
    void theAcsEstablishesTheSessionAndRedirectsHome() throws Exception {
        mvc.perform(post(ACS_URI)
                        .param("SAMLResponse", "<b64>").param("RelayState", "<relay>"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/"));

        // RelayState is pinned because it is the ONLY carrier of the tenant on this path: the ACS is a
        // cross-site POST, so no session cookie arrives and nothing else says which login this answers.
        verify(service).completeSaml(eq("corp"), eq("<b64>"), eq("<relay>"), any(), any());
    }

    @Test
    void anAcsPostWithNoAssertionNeverReachesTheService() throws Exception {
        mvc.perform(post(ACS_URI).param("RelayState", "<relay>"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", FAILURE_REDIRECT));

        verify(service, never()).completeSaml(any(), any(), any(), any(), any());
    }

    @Test
    void aRefusedAssertionRendersTheFailureRedirectRatherThanAnError() throws Exception {
        doThrow(new UnauthorizedException()).when(service)
                .completeSaml(any(), any(), any(), any(), any());

        mvc.perform(post(ACS_URI).param("SAMLResponse", "<b64>"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", FAILURE_REDIRECT));
    }

    @Test
    void anUnexpectedAcsFailureAlsoRendersTheFailureRedirect() throws Exception {
        // A browser-navigation endpoint must never leak a stack-trace page, whatever went wrong.
        doThrow(new IllegalStateException("redis down")).when(service)
                .completeSaml(any(), any(), any(), any(), any());

        mvc.perform(post(ACS_URI).param("SAMLResponse", "<b64>"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", FAILURE_REDIRECT));
    }
}
