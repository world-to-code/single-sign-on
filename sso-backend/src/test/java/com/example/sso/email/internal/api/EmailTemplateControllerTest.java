package com.example.sso.email.internal.api;

import com.example.sso.email.internal.application.EmailTemplatePreview;
import com.example.sso.email.internal.application.EmailTemplateService;
import com.example.sso.email.template.EmailEvent;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer contract of the email-template admin API: {@code @Valid} bounds the request shape (required
 * subject/html, https-only logo), the write endpoints stay {@code @RequireStepUp} behind the MUTATING
 * permission (never a {@code *:read}), and preview renders. Gate ENFORCEMENT is proven elsewhere
 * ({@code StepUpInterceptorTest}); this pins the declarations and the validation.
 */
class EmailTemplateControllerTest {

    private final EmailTemplateService service = mock(EmailTemplateService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EmailTemplateController(service)).build();
    }

    private String body(String subject, String html, String logoUrl) {
        return """
                {"subject":%s,"htmlBody":%s,"textBody":"t","logoUrl":%s}"""
                .formatted(quote(subject), quote(html), quote(logoUrl));
    }

    private String quote(String value) {
        return value == null ? "null" : '"' + value + '"';
    }

    private void expectPutStatus(String requestBody, int expected) throws Exception {
        mvc.perform(put("/api/admin/email-templates/EMAIL_VERIFICATION_CODE")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().is(expected));
    }

    @Test
    void aValidBodyIsAccepted() throws Exception {
        expectPutStatus(body("Verify {{code}}", "<b>{{code}}</b>", "https://cdn.example/l.png"), 200);
    }

    @Test
    void aBlankSubjectOrHtmlIsRejected() throws Exception {
        expectPutStatus(body("", "<b>x</b>", null), 400);
        expectPutStatus(body("Subj", "", null), 400);
    }

    @Test
    void aNonHttpsLogoUrlIsRejected() throws Exception {
        expectPutStatus(body("Subj", "<b>x</b>", "http://cdn.example/l.png"), 400);
    }

    @Test
    void previewRendersAndReturnsTheResult() throws Exception {
        when(service.preview(eq(EmailEvent.EMAIL_VERIFICATION_CODE), any()))
                .thenReturn(new EmailTemplatePreview("Verify 123456", "<b>123456</b>", "code 123456"));

        mvc.perform(post("/api/admin/email-templates/EMAIL_VERIFICATION_CODE/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Verify {{code}}", "<b>{{code}}</b>", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Verify 123456"))
                .andExpect(jsonPath("$.html").value("<b>123456</b>"));
    }

    @Test
    void theWriteEndpointsAreStepUpGatedBehindTheMutatingPermissionAndReadsBehindTheReadPermission()
            throws Exception {
        assertThat(permissionOf("update", EmailEvent.class, EmailTemplateRequest.class))
                .isEqualTo(Permissions.EMAIL_TEMPLATE_UPDATE);
        assertThat(permissionOf("delete", EmailEvent.class)).isEqualTo(Permissions.EMAIL_TEMPLATE_UPDATE);
        assertThat(permissionOf("list")).isEqualTo(Permissions.EMAIL_TEMPLATE_READ);
        assertThat(permissionOf("preview", EmailEvent.class, EmailTemplateRequest.class))
                .isEqualTo(Permissions.EMAIL_TEMPLATE_READ);

        assertThat(isStepUpGated("update", EmailEvent.class, EmailTemplateRequest.class)).isTrue();
        assertThat(isStepUpGated("delete", EmailEvent.class)).isTrue();
        assertThat(isStepUpGated("preview", EmailEvent.class, EmailTemplateRequest.class)).isFalse(); // a read
    }

    private String permissionOf(String method, Class<?>... params) throws Exception {
        RequirePermission annotation = EmailTemplateController.class.getMethod(method, params)
                .getAnnotation(RequirePermission.class);
        assertThat(annotation).as("%s requires a permission", method).isNotNull();
        return annotation.value();
    }

    private boolean isStepUpGated(String method, Class<?>... params) throws Exception {
        return EmailTemplateController.class.getMethod(method, params).isAnnotationPresent(RequireStepUp.class);
    }
}
