package com.example.sso.portal.internal.console.api;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.internal.console.application.AppSessionService;
import com.example.sso.portal.internal.console.application.AppSessionView;
import com.example.sso.portal.internal.console.application.PortalService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the portal app-session endpoints. The IDOR-critical property is that the acting user is
 * taken from the AUTHENTICATED principal, never from the request body ({@code AppSessionLogoutRequest} carries
 * no username) — pinned here so a refactor to a body-supplied principal would fail. (The anonymous→401 denial
 * for {@code /api/portal/**} is enforced by the security chain, not this standalone MVC setup.)
 */
class PortalControllerTest {

    private static final UsernamePasswordAuthenticationToken ALICE =
            new UsernamePasswordAuthenticationToken("alice", null);

    private final AppSessionService appSessions = mock(AppSessionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PortalController(mock(PortalService.class), appSessions)).build();
    }

    @Test
    void listReturnsTheAuthenticatedUsersOwnAppSessions() throws Exception {
        when(appSessions.list("alice"))
                .thenReturn(List.of(new AppSessionView("OIDC", "billing", "Billing", true)));

        mvc.perform(get("/api/portal/app-sessions").principal(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].appId").value("billing"))
                .andExpect(jsonPath("$[0].oneClickLogoutSupported").value(true));

        verify(appSessions).list("alice"); // acting user is the principal, never a request param
    }

    @Test
    void logoutActsAsThePrincipalOnTheBodysAppAndReturnsTheRefreshedList() throws Exception {
        when(appSessions.list("alice")).thenReturn(List.of());

        mvc.perform(post("/api/portal/app-sessions/logout").principal(ALICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"OIDC\",\"appId\":\"billing\"}"))
                .andExpect(status().isOk());

        // Username from the principal ("alice"), app from the body — never a body-supplied username.
        verify(appSessions).logout(eq("alice"), eq(AppType.OIDC), eq("billing"));
        verify(appSessions).list("alice"); // response echoes the refreshed list
    }

    @Test
    void logoutRejectsABlankAppId() throws Exception {
        mvc.perform(post("/api/portal/app-sessions/logout").principal(ALICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"OIDC\",\"appId\":\"\"}"))
                .andExpect(status().isBadRequest()); // @Valid @NotBlank
    }
}
