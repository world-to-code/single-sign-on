package com.example.sso.branding.internal.api;

import com.example.sso.branding.internal.application.BrandingService;
import com.example.sso.branding.internal.application.BrandingView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer contract of the admin branding API: {@code @Valid} bounds the shape (https logo, {@code #RRGGBB}
 * accent, capped name), and the write endpoints stay {@code @RequireStepUp} behind the MUTATING permission
 * (never a {@code *:read}). Enforcement is proven elsewhere; this pins the declarations and validation.
 */
class AdminBrandingControllerTest {

    private final BrandingService service = mock(BrandingService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminBrandingController(service)).build();
    }

    private String body(String logoUrl, String accent, String name) {
        return """
                {"logoUrl":%s,"accentColor":%s,"productName":%s}"""
                .formatted(quote(logoUrl), quote(accent), quote(name));
    }

    private String quote(String value) {
        return value == null ? "null" : '"' + value + '"';
    }

    private void expectPutStatus(String requestBody, int expected) throws Exception {
        mvc.perform(put("/api/admin/branding").contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().is(expected));
    }

    @Test
    void aValidBodyIsAccepted() throws Exception {
        when(service.get()).thenReturn(new BrandingView(true, "https://cdn.example/l.png", "#123abc", "Acme"));
        expectPutStatus(body("https://cdn.example/l.png", "#123abc", "Acme"), 200);
    }

    @Test
    void aNonHttpsLogoIsRejected() throws Exception {
        expectPutStatus(body("http://cdn.example/l.png", "#123abc", "Acme"), 400);
    }

    @Test
    void aNonHexAccentIsRejected() throws Exception {
        expectPutStatus(body(null, "red", "Acme"), 400);
        expectPutStatus(body(null, "#ABC", "Acme"), 400);
    }

    @Test
    void anOversizedProductNameIsRejected() throws Exception {
        expectPutStatus(body(null, "#123abc", "x".repeat(65)), 400);
    }

    @Test
    void theWriteEndpointsAreStepUpGatedBehindTheMutatingPermissionAndReadsBehindTheReadPermission()
            throws Exception {
        assertThat(permissionOf("update", BrandingRequest.class)).isEqualTo(Permissions.BRANDING_UPDATE);
        assertThat(permissionOf("delete")).isEqualTo(Permissions.BRANDING_UPDATE);
        assertThat(permissionOf("get")).isEqualTo(Permissions.BRANDING_READ);

        assertThat(isStepUpGated("update", BrandingRequest.class)).isTrue();
        assertThat(isStepUpGated("delete")).isTrue();
    }

    private String permissionOf(String method, Class<?>... params) throws Exception {
        RequirePermission annotation = AdminBrandingController.class.getMethod(method, params)
                .getAnnotation(RequirePermission.class);
        assertThat(annotation).as("%s requires a permission", method).isNotNull();
        return annotation.value();
    }

    private boolean isStepUpGated(String method, Class<?>... params) throws Exception {
        return AdminBrandingController.class.getMethod(method, params).isAnnotationPresent(RequireStepUp.class);
    }
}
