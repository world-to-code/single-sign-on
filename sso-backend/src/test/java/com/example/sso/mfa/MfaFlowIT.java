package com.example.sso.mfa;

import com.example.sso.mfa.internal.application.TotpService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the factor-aware MFA flow over the JSON auth API: a new user is routed through
 * email verification then TOTP enrollment; a verified+enrolled user completes the TOTP
 * challenge to reach a fully-authenticated session.
 */
@AutoConfigureMockMvc
class MfaFlowIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService userService;
    @Autowired
    MfaService mfaService;
    @Autowired
    TotpService totpService;

    @Test
    void defaultPolicyRequiresTotpAfterPassword() throws Exception {
        userService.createUser(new NewUser("mfa-new", "mfa-new@example.com", "New", "pw-new-12!",
                Set.of("ROLE_USER")));

        mvc.perform(login("mfa-new", "pw-new-12!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("FACTOR"))
                .andExpect(jsonPath("$.pendingFactors[0]").value("TOTP"));
    }

    @Test
    void completingTotpReachesFullyAuthenticated() throws Exception {
        UserAccount user = userService.createUser(new NewUser("mfa-ok", "mfa-ok@example.com", "Ok", "pw-ok-12!",
                Set.of("ROLE_USER")));
        TotpEnrollment enrollment = mfaService.newEnrollment(user);
        mfaService.confirmEnrollment(user, enrollment.secret(),
                totpService.generateCodeAt(enrollment.secret(), System.currentTimeMillis() - 30_000));

        Cookie session = sessionCookie(mvc.perform(login("mfa-ok", "pw-ok-12!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("FACTOR"))
                .andReturn(), null);

        // Policy not satisfied yet -> protected API forbidden.
        mvc.perform(get("/api/me").cookie(session)).andExpect(status().isForbidden());

        // Complete the TOTP factor -> policy satisfied (MFA_COMPLETE). Auth rotates the session id, so
        // carry the refreshed cookie.
        session = sessionCookie(mvc.perform(post("/api/auth/factors/TOTP/verify").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + totpService.generateCurrentCode(enrollment.secret()) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("DONE"))
                .andReturn(), session);

        // Now fully authenticated.
        mvc.perform(get("/api/me").cookie(session)).andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder login(String user, String password) {
        return post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}");
    }
}
