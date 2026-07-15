package com.example.sso.config;

import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The deployment-plane split ({@code sso.plane=auth}): a public auth-runtime deployment still serves the
 * login/auth surface but NOT the management surface (the admin console shell and {@code /api/admin/**}) — so the
 * admin plane is reachable only on a separate deployment. The default (all) is byte-for-byte the current server
 * and is covered by every other IT; here we pin the auth-plane denial.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "sso.plane=auth")
class PlaneSplitIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theAuthPlaneServesLoginButNotTheAdminConsoleShell() throws Exception {
        // The login page is part of the auth plane and is still served (would be 200 on any plane).
        mvc.perform(get("/login").accept(MediaType.TEXT_HTML)).andExpect(status().isOk());
        // The admin console SPA shell — permitAll (200) on the default plane — is denied here.
        mvc.perform(get("/admin").accept(MediaType.TEXT_HTML)).andExpect(status().isUnauthorized());
    }

    @Test
    void theAuthPlaneDoesNotServeTheAdminApi() throws Exception {
        mvc.perform(get("/api/admin/mapping-rules")).andExpect(status().isUnauthorized());
    }
}
