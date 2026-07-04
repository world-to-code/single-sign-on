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
    void anonymousSessionStartsWithIdentify() throws Exception {
        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.next").value("IDENTIFY"));
    }

    @Test
    void identifyResolvesPolicyFirstFactorWithoutExposingRoles() throws Exception {
        // Identifier-first: email in -> the policy's first factor (password), and NO roles yet.
        mvc.perform(post("/api/auth/identify").with(csrf())
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
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordLoginEstablishesSessionButProtectedApiNeedsMfa() throws Exception {
        // Default policy is password -> TOTP, so after login the next step is a TOTP factor.
        Cookie session = sessionCookie(mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("FACTOR"))
                .andExpect(jsonPath("$.pendingFactors[0]").value("TOTP"))
                .andReturn(), null);

        // The auth policy is not yet satisfied (no MFA_COMPLETE), so a protected endpoint is forbidden.
        mvc.perform(get("/api/me").cookie(session))
                .andExpect(status().isForbidden());
    }
}
