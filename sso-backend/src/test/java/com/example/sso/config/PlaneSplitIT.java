package com.example.sso.config;

import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The deployment-plane split ({@code sso.plane=auth}): a public auth-runtime deployment DENIES the management
 * surface (the admin API), so it is reachable only on a separate admin-plane deployment. The SPA shells are
 * served by the nginx edge (not this backend), so the split is enforced here at the API level — each deployment
 * simply serves the matching SPA bundle at its own edge. The default (all) plane is byte-for-byte the current
 * server and is covered by every other IT; here we pin the auth-plane denial.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "sso.plane=auth")
class PlaneSplitIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theAuthPlaneDeniesTheAdminApiButRemainsAFunctioningRuntime() throws Exception {
        // The management surface (admin API) is denied on the auth plane — served only on a separate admin plane.
        mvc.perform(get("/api/admin/mapping-rules")).andExpect(status().isUnauthorized());
        // The rest of the runtime still serves: the liveness probe is reachable and UP (it reflects the app
        // itself, not external stores, so it does not flake on a missing mail/db dependency in the test env).
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
