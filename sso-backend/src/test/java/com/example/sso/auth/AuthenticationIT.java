package com.example.sso.auth;

import com.example.sso.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the JSON auth API: anonymous session state, password login establishing a
 * session, and that protected APIs require the full MFA factors.
 */
@AutoConfigureMockMvc
class AuthenticationIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousSessionStartsWithOrganization() throws Exception {
        // Tenant-first: a fresh visitor picks their organization before anything else.
        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.next").value("ORGANIZATION"));
    }

    @Test
    void identifyWithinTheOrgResolvesPolicyFirstFactorWithoutExposingRoles() throws Exception {
        // Resolve the (seeded) default org first, then identify the admin within it -> the policy's first
        // factor (password), and NO roles exposed yet.
        Cookie session = sessionCookie(mvc.perform(post("/api/auth/organization").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"default\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("IDENTIFY"))
                .andReturn(), null);

        mvc.perform(post("/api/auth/identify").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.next").value("FACTOR"))
                .andExpect(jsonPath("$.pendingFactors[0]").value("PASSWORD"))
                .andExpect(jsonPath("$.roles").isEmpty());
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        Cookie session = resolveDefaultOrg(mvc);
        mvc.perform(post("/api/auth/login").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithoutAResolvedOrgIsRefused() throws Exception {
        // Tenant-first: credentials alone must not authenticate — the org must be resolved first.
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123!\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordLoginEstablishesSessionButProtectedApiNeedsMfa() throws Exception {
        // Default policy is password -> TOTP, so after login the next step is a TOTP factor.
        Cookie org = resolveDefaultOrg(mvc);
        Cookie session = sessionCookie(mvc.perform(post("/api/auth/login").cookie(org).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("FACTOR"))
                .andExpect(jsonPath("$.pendingFactors[0]").value("TOTP"))
                .andReturn(), org);

        // The auth policy is not yet satisfied (no MFA_COMPLETE), so a protected endpoint is forbidden.
        mvc.perform(get("/api/me").cookie(session))
                .andExpect(status().isForbidden());
    }
}
