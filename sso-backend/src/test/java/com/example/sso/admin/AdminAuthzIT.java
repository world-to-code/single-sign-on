package com.example.sso.admin;

import com.example.sso.mfa.MfaService;
import com.example.sso.mfa.TotpEnrollment;
import com.example.sso.mfa.internal.application.TotpService;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
    OrganizationService organizations;
    @Autowired
    MfaService mfaService;
    @Autowired
    TotpService totpService;

    @Test
    void nonAdminIsForbiddenFromAdminApi() throws Exception {
        UserAccount user = userService.createUser(new NewUser("plainuser", "plain@example.com", "Plain",
                "pw-plain-1!", Set.of("ROLE_USER")));
        userService.markEmailVerified(user.getId());
        organizations.addMember(organizations.findBySlug(DEFAULT_ORG_SLUG).orElseThrow().getId(), user.getId());
        TotpEnrollment enrollment = mfaService.newEnrollment(user);
        mfaService.confirmEnrollment(user, enrollment.secret(),
                totpService.generateCodeAt(enrollment.secret(), System.currentTimeMillis() - 30_000));

        Cookie org = resolveDefaultOrg(mvc);
        Cookie session = sessionCookie(mvc.perform(post("/api/auth/login").cookie(org).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"plainuser\",\"password\":\"pw-plain-1!\"}"))
                .andReturn(), org);
        session = sessionCookie(mvc.perform(post("/api/auth/factors/TOTP/verify").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + totpService.generateCurrentCode(enrollment.secret()) + "\"}"))
                .andExpect(status().isOk())
                .andReturn(), session);

        // Fully authenticated, but lacks ROLE_ADMIN -> admin API forbidden.
        mvc.perform(get("/api/admin/users").cookie(session)).andExpect(status().isForbidden());
        // Ordinary authenticated endpoint is allowed.
        mvc.perform(get("/api/me").cookie(session)).andExpect(status().isOk());
    }
}
