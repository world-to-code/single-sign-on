package com.example.sso.branding.internal.api;

import com.example.sso.branding.AuthScreen;
import com.example.sso.branding.ScreenCopy;
import com.example.sso.branding.internal.application.ScreenCopyService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.shared.security.RequirePermission;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer contract of the admin screen-copy API. The screen is a PATH VARIABLE bound to an enum, so an
 * unknown screen must be refused before any service call — otherwise a row lands that no screen will ever
 * render. {@code @Valid} bounds the body's caps, and the write endpoints stay {@code @RequireStepUp} behind
 * the MUTATING permission. Enforcement of those annotations is proven elsewhere; this pins the declarations,
 * the binding and the validation.
 */
class AdminScreenCopyControllerTest {

    private final ScreenCopyService service = mock(ScreenCopyService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminScreenCopyController(service)).build();
    }

    private String body(String headline, String subtext, String footer, String helpUrl) {
        return """
                {"headline":%s,"subtext":%s,"footer":%s,"helpUrl":%s}"""
                .formatted(quote(headline), quote(subtext), quote(footer), quote(helpUrl));
    }

    private String quote(String value) {
        return value == null ? "null" : '"' + value + '"';
    }

    @Test
    void getReturnsTheActingTiersWordingKeyedByScreen() throws Exception {
        when(service.get()).thenReturn(Map.of(AuthScreen.LOGIN,
                new ScreenCopy("Sign in to Acme", null, null, null)));

        mvc.perform(get("/api/admin/branding/screens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.LOGIN.headline").value("Sign in to Acme"));
    }

    @Test
    void putWritesTheScreenNamedInThePath() throws Exception {
        mvc.perform(put("/api/admin/branding/screens/LOGIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Sign in to Acme", "Use your work account", null, null)))
                .andExpect(status().isOk());

        ArgumentCaptor<ScreenCopy> written = ArgumentCaptor.captor();
        verify(service).update(eq(AuthScreen.LOGIN), written.capture());
        assertThat(written.getValue().headline()).isEqualTo("Sign in to Acme");
    }

    /** A screen the SPA does not implement must not reach the service, which would store an unusable row. */
    @Test
    void anUnknownScreenIsRefusedBeforeTheServiceIsCalled() throws Exception {
        mvc.perform(put("/api/admin/branding/screens/NOT_A_SCREEN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("x", null, null, null)))
                .andExpect(status().is4xxClientError());

        verify(service, never()).update(any(), any());
    }

    @Test
    void anOverlongHeadlineIsRefusedByTheRequestBinding() throws Exception {
        mvc.perform(put("/api/admin/branding/screens/LOGIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("x".repeat(121), null, null, null)))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(any(), any());
    }

    /** Each capped field asserted on its own, so a cap declared on one and forgotten on another is visible. */
    @Test
    void anOverlongSubtextOrFooterIsRefusedByTheRequestBinding() throws Exception {
        mvc.perform(put("/api/admin/branding/screens/LOGIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "x".repeat(301), null, null)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/branding/screens/LOGIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, null, "x".repeat(201), null)))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(any(), any());
    }

    @Test
    void aNonHttpsHelpLinkIsRefusedByTheRequestBinding() throws Exception {
        mvc.perform(put("/api/admin/branding/screens/LOGIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, null, null, "http://help.acme")))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(any(), any());
    }

    /** At the cap is accepted, so the declared bound is `max` and not `max - 1`. */
    @Test
    void aFieldExactlyAtItsCapIsAccepted() throws Exception {
        mvc.perform(put("/api/admin/branding/screens/LOGIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("x".repeat(120), "y".repeat(300), "z".repeat(200), null)))
                .andExpect(status().isOk());

        verify(service).update(eq(AuthScreen.LOGIN), any());
    }

    @Test
    void deleteDropsTheScreenNamedInThePath() throws Exception {
        mvc.perform(delete("/api/admin/branding/screens/CONSENT"))
                .andExpect(status().isNoContent());

        verify(service).delete(AuthScreen.CONSENT);
    }

    /**
     * The declarations the class docblock claims. A read permission on a WRITE would let anyone who can view
     * branding rewrite what the login screen says; losing the step-up would let a stale console session do it.
     * Reflection rather than an HTTP test because the enforcement lives in an interceptor proven elsewhere —
     * what is unpinned here is the declaration.
     */
    @Test
    void theWriteEndpointsAreStepUpGatedBehindTheMutatingPermissionAndReadsBehindTheReadPermission()
            throws Exception {
        assertThat(permissionOf("update", AuthScreen.class, ScreenCopyRequest.class))
                .isEqualTo(Permissions.BRANDING_UPDATE);
        assertThat(permissionOf("delete", AuthScreen.class)).isEqualTo(Permissions.BRANDING_UPDATE);
        assertThat(permissionOf("get")).isEqualTo(Permissions.BRANDING_READ);

        assertThat(isStepUpGated("update", AuthScreen.class, ScreenCopyRequest.class)).isTrue();
        assertThat(isStepUpGated("delete", AuthScreen.class)).isTrue();
        assertThat(isStepUpGated("get")).as("a read is never step-up gated").isFalse();
    }

    private String permissionOf(String method, Class<?>... params) throws Exception {
        RequirePermission annotation = AdminScreenCopyController.class.getMethod(method, params)
                .getAnnotation(RequirePermission.class);
        assertThat(annotation).as("%s requires a permission", method).isNotNull();
        return annotation.value();
    }

    private boolean isStepUpGated(String method, Class<?>... params) throws Exception {
        return AdminScreenCopyController.class.getMethod(method, params).isAnnotationPresent(RequireStepUp.class);
    }
}
