package com.example.sso.session;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.mfa.MfaService;
import com.example.sso.mfa.TotpEnrollment;
import com.example.sso.mfa.internal.application.TotpService;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end: a real password+TOTP login must land a fully-authenticated session in Redis, indexed by
 * principal name (so concurrent-session control works), carrying the {@code MFA_COMPLETE} authority and
 * the OIDC {@code sid} marker (so back-channel logout can find the session on termination).
 */
@AutoConfigureMockMvc
class RedisLoginSessionIT extends AbstractIntegrationTest {

    static final String SECURITY_CONTEXT_ATTR = "SPRING_SECURITY_CONTEXT";

    @Autowired
    MockMvc mvc;
    @Autowired
    RedisIndexedSessionRepository sessions;
    @Autowired
    UserService userService;
    @Autowired
    OrganizationService organizations;
    @Autowired
    MfaService mfaService;
    @Autowired
    TotpService totpService;

    @Test
    void aCompletedLoginPersistsAFullSessionInRedisIndexedByPrincipal() throws Exception {
        String username = "redis-login-user";
        UserAccount user = userService.createUser(new NewUser(username, username + "@example.com", username,
                "pw-redis-1!", Set.of("ROLE_USER")));
        userService.markEmailVerified(user.getId());
        organizations.addMember(organizations.findBySlug(DEFAULT_ORG_SLUG).orElseThrow().getId(), user.getId());
        TotpEnrollment enrollment = mfaService.newEnrollment(user);
        mfaService.confirmEnrollment(user, enrollment.secret(),
                totpService.generateCodeAt(enrollment.secret(), System.currentTimeMillis() - 30_000));

        Cookie org = resolveDefaultOrg(mvc);
        Cookie session = sessionCookie(mvc.perform(post("/api/auth/login").cookie(org).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw-redis-1!\"}"))
                .andExpect(status().isOk())
                .andReturn(), org);
        mvc.perform(post("/api/auth/factors/TOTP/verify").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + totpService.generateCurrentCode(enrollment.secret()) + "\"}"))
                .andExpect(status().isOk());

        Map<String, ? extends Session> byPrincipal = sessions.findByPrincipalName(username);
        assertThat(byPrincipal).as("the completed session is indexed by principal in Redis").hasSize(1);

        SecurityContext context = byPrincipal.values().iterator().next().getAttribute(SECURITY_CONTEXT_ATTR);
        assertThat(context).isNotNull();
        Set<String> authorities = context.getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        assertThat(authorities).contains(Factors.MFA_COMPLETE);
        assertThat(authorities).as("carries the sid marker for back-channel logout")
                .anyMatch(a -> a.startsWith(Factors.SID_PREFIX));
    }
}
