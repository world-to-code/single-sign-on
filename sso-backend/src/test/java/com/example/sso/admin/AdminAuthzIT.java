package com.example.sso.admin;

import com.example.sso.mfa.MfaService;
import com.example.sso.mfa.TotpEnrollment;
import com.example.sso.mfa.internal.application.TotpService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC enforcement: a fully MFA-authenticated non-admin user is denied the admin API but
 * can reach ordinary authenticated endpoints.
 */
@AutoConfigureMockMvc
class AdminAuthzIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService userService;
    @Autowired
    MfaService mfaService;
    @Autowired
    TotpService totpService;

    @Test
    void nonAdminIsForbiddenFromAdminApi() throws Exception {
        UserAccount user = userService.createUser("plainuser", "plain@example.com", "Plain", "pw-plain-1!", Set.of("ROLE_USER"));
        userService.markEmailVerified(user.getId());
        TotpEnrollment enrollment = mfaService.newEnrollment(user);
        mfaService.confirmEnrollment(user, enrollment.secret(),
                totpService.generateCodeAt(enrollment.secret(), System.currentTimeMillis() - 30_000));

        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"plainuser\",\"password\":\"pw-plain-1!\"}"))
                .andReturn().getRequest().getSession();
        mvc.perform(post("/api/auth/factors/TOTP/verify").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + totpService.generateCurrentCode(enrollment.secret()) + "\"}"))
                .andExpect(status().isOk());

        // Fully authenticated, but lacks ROLE_ADMIN -> admin API forbidden.
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
        // Ordinary authenticated endpoint is allowed.
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());
    }
}
