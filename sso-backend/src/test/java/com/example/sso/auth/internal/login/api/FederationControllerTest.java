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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the federation browser endpoints: {@code /start} 302s to the upstream authorization URI; the
 * callback establishes the session and 302s to {@code /} on success, and to {@code /?login_error=federation} on
 * an upstream error, a missing code, or a validation failure ({@code ApiException}) — never a raw JSON error in
 * the address bar, and never an account-existence oracle. A standalone MVC setup exercises the redirects in
 * isolation from security (permitAll + CSRF-exempt GETs are proven by the security config).
 */
class FederationControllerTest {

    private final FederatedAuthenticationService service = mock(FederatedAuthenticationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FederationController(service)).build();
    }

    @Test
    void startRedirectsToTheUpstreamAuthorizationUri() throws Exception {
        when(service.start(eq("google"), any())).thenReturn("https://accounts.example.com/authorize?x=1");

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
                .andExpect(redirectedUrl("/?login_error=federation"));

        verify(service, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void aMissingCodeRedirectsToFailureWithoutCompleting() throws Exception {
        mvc.perform(get("/api/auth/federation/google/callback").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/?login_error=federation"));

        verify(service, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void aValidationFailureRedirectsToFailureRatherThanLeakingAJsonError() throws Exception {
        doThrow(new UnauthorizedException()).when(service).complete(eq("google"), any(), any(), any(), any());

        mvc.perform(get("/api/auth/federation/google/callback").param("code", "bad").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/?login_error=federation"));
    }
}
