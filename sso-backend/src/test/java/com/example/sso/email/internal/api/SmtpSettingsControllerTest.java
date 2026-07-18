package com.example.sso.email.internal.api;

import com.example.sso.email.internal.application.SmtpSettingsService;
import com.example.sso.email.internal.application.SmtpSettingsView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer contract of the SMTP settings admin API: {@code @Valid} bounds the request shape (host/port), the
 * write endpoints stay {@code @RequireStepUp}-gated (credential-bearing) behind the mutating permission, and —
 * the load-bearing secret invariant — the response body NEVER echoes the submitted password back. Enforcement
 * of the gates is proven elsewhere ({@code StepUpInterceptorTest}); this pins the declarations and the contract.
 * A standalone MVC setup exercises validation in isolation from security.
 */
class SmtpSettingsControllerTest {

    private final SmtpSettingsService service = mock(SmtpSettingsService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SmtpSettingsController(service)).build();
    }

    private void expectPutStatus(String body, int expected) throws Exception {
        mvc.perform(put("/api/admin/smtp-settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String body(String host, int port) {
        return """
                {"host":"%s","port":%d,"username":"postmaster","password":"s3cret","fromAddress":"a@b.example"}"""
                .formatted(host, port);
    }

    @Test
    void aValidBodyIsAccepted() throws Exception {
        when(service.get()).thenReturn(new SmtpSettingsView(true, "smtp.acme.example", 587, "postmaster",
                "a@b.example", true));
        expectPutStatus(body("smtp.acme.example", 587), 200);
    }

    @Test
    void aBlankHostIsRejected() throws Exception {
        expectPutStatus(body("", 587), 400);
    }

    @Test
    void anOutOfRangePortIsRejected() throws Exception {
        expectPutStatus(body("smtp.acme.example", 0), 400);
        expectPutStatus(body("smtp.acme.example", 70000), 400);
    }

    @Test
    void theResponseNeverEchoesTheSubmittedPassword() throws Exception {
        when(service.get()).thenReturn(new SmtpSettingsView(true, "smtp.acme.example", 587, "postmaster",
                "a@b.example", true));

        mvc.perform(put("/api/admin/smtp-settings").contentType(MediaType.APPLICATION_JSON)
                        .content(body("smtp.acme.example", 587)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString("s3cret"))));
    }

    @Test
    void theWriteEndpointsAreStepUpGatedBehindTheMutatingPermissionAndReadsBehindTheReadPermission()
            throws Exception {
        // A write must NEVER be gated behind a *:read permission (OWASP A01) — assert the exact permission
        // VALUE, not merely that some @RequirePermission is present.
        assertThat(permissionOf("update", SmtpSettingsRequest.class)).isEqualTo(Permissions.SMTP_SETTINGS_UPDATE);
        assertThat(permissionOf("delete")).isEqualTo(Permissions.SMTP_SETTINGS_UPDATE);
        assertThat(permissionOf("get")).isEqualTo(Permissions.SMTP_SETTINGS_READ);

        assertThat(isStepUpGated("update", SmtpSettingsRequest.class)).as("update is step-up gated").isTrue();
        assertThat(isStepUpGated("delete")).as("delete is step-up gated").isTrue();
    }

    private String permissionOf(String method, Class<?>... params) throws Exception {
        RequirePermission annotation = SmtpSettingsController.class.getMethod(method, params)
                .getAnnotation(RequirePermission.class);
        assertThat(annotation).as("%s requires a permission", method).isNotNull();
        return annotation.value();
    }

    private boolean isStepUpGated(String method, Class<?>... params) throws Exception {
        return SmtpSettingsController.class.getMethod(method, params).isAnnotationPresent(RequireStepUp.class);
    }
}
