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
import org.hamcrest.Matchers;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Negative-access behavior of the admin API, layer by layer — proving each rejection is the INTENDED
 * one for the intended reason:
 * <ul>
 *   <li>no session → 401 (must authenticate);</li>
 *   <li>authenticated but MFA not completed → 403 (the {@code MFA_COMPLETE} URL gate);</li>
 *   <li>fully authenticated but lacking the endpoint's permission → 403 ({@code @RequirePermission});</li>
 *   <li>fully authenticated but no admin-console elevation → 401 with the RFC 9470
 *       {@code WWW-Authenticate: Bearer error="insufficient_user_authentication"} step-up challenge.</li>
 * </ul>
 * NB: MockMvc leaves {@code getServletPath()} empty, so the servlet-path-keyed {@code AdminElevationFilter}
 * is skipped unless the test sets it — which lets each layer be isolated.
 */
@AutoConfigureMockMvc
class AdminAccessRejectionIT extends AbstractIntegrationTest {

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

    private static final String ADMIN_URI = "/api/admin/resources";

    @Test
    void anonymousRequestMustAuthenticate() throws Exception {
        mvc.perform(get(ADMIN_URI)).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedWithoutMfaIsRejectedByTheMfaGate() throws Exception {
        // Password step done, MFA step NOT completed → no MFA_COMPLETE authority.
        createUser("no-mfa-user");
        Cookie session = login("no-mfa-user");

        mvc.perform(get(ADMIN_URI).cookie(session)).andExpect(status().isForbidden());
        // Sanity: the same half-authenticated session cannot reach an ordinary MFA-gated endpoint either.
        mvc.perform(get("/api/me").cookie(session)).andExpect(status().isForbidden());
    }

    @Test
    void fullyAuthenticatedWithoutThePermissionIsForbidden() throws Exception {
        // MFA-complete but only ROLE_USER (no resource:read). Elevation filter skipped (empty servletPath),
        // so the request reaches @RequirePermission → 403.
        Cookie session = mfaSession("perm-less-user");

        mvc.perform(get(ADMIN_URI).cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/me").cookie(session)).andExpect(status().isOk()); // ordinary endpoint fine
    }

    @Test
    void fullyAuthenticatedWithoutElevationGetsTheStepUpChallenge() throws Exception {
        Cookie session = mfaSession("unelevated-user");

        // With the real servlet path set, AdminElevationFilter runs: no admin-console bearer → 401 + challenge.
        mvc.perform(get(ADMIN_URI).cookie(session).with(servletPath(ADMIN_URI)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        Matchers.containsString("insufficient_user_authentication")));
    }

    @Test
    void garbageElevationBearerIsRejected() throws Exception {
        Cookie session = mfaSession("garbage-token-user");

        mvc.perform(get(ADMIN_URI).cookie(session).with(servletPath(ADMIN_URI))
                        .header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    // --- session builders (real login + MFA flow) ---

    private Cookie login(String username) throws Exception {
        Cookie org = resolveDefaultOrg(mvc);
        return sessionCookie(mvc.perform(post("/api/auth/login").cookie(org).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw-neg-1!\"}"))
                .andReturn(), org);
    }

    private Cookie mfaSession(String username) throws Exception {
        UserAccount user = createUser(username);
        TotpEnrollment enrollment = mfaService.newEnrollment(user);
        mfaService.confirmEnrollment(user, enrollment.secret(),
                totpService.generateCodeAt(enrollment.secret(), System.currentTimeMillis() - 30_000));

        Cookie session = login(username);
        // MFA completion rotates the session id (session-fixation defense), so carry the refreshed cookie.
        return sessionCookie(mvc.perform(post("/api/auth/factors/TOTP/verify").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + totpService.generateCurrentCode(enrollment.secret()) + "\"}"))
                .andExpect(status().isOk())
                .andReturn(), session);
    }

    private UserAccount createUser(String username) {
        UserAccount user = userService.createUser(new NewUser(username, username + "@example.com", username,
                "pw-neg-1!", Set.of("ROLE_USER")));
        userService.markEmailVerified(user.getId());
        organizations.addMember(organizations.findBySlug(DEFAULT_ORG_SLUG).orElseThrow().getId(), user.getId());
        return user;
    }

    private RequestPostProcessor servletPath(String path) {
        return request -> {
            request.setServletPath(path);
            return request;
        };
    }
}
