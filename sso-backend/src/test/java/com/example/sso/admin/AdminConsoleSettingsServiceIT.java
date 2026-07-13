package com.example.sso.admin;

import com.example.sso.admin.internal.portalsettings.application.AdminConsoleSettingsService;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.portal.binding.AdminConsoleConfigService;
import com.example.sso.portal.binding.AdminConsoleConfigView;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.binding.PortalSessionBinding;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The admin console's session-policy selection and its console-only config (elevation TTL + IP allowlist) are
 * written in ONE transaction: a malformed CIDR must roll BOTH back, never leave a new session policy applied
 * with a stale allowlist (a half-applied, fail-open network control).
 */
class AdminConsoleSettingsServiceIT extends AbstractIntegrationTest {

    @Autowired
    AdminConsoleSettingsService consoleSettings;
    @Autowired
    PortalSessionBinding portals;
    @Autowired
    AdminConsoleConfigService consoleConfig;
    @Autowired
    SessionPolicyService sessionPolicies;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;

    private UUID orgA;

    @AfterEach
    void tearDown() {
        if (orgA != null) {
            organizations.delete(orgA);
        }
    }

    private UUID org() {
        String slug = "console-set-it-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug)).id();
    }

    private UUID defaultPolicyOf(UUID orgId) {
        await().until(() -> orgContext.callInOrg(orgId, () -> !sessionPolicies.listAll().isEmpty()));
        return orgContext.callInOrg(orgId, () -> sessionPolicies.listAll().getFirst()).getId();
    }

    @Test
    void aMalformedCidrRollsBackTheWholeConsoleSettingsUpdate() {
        orgA = org();
        UUID policy = defaultPolicyOf(orgA);
        // Establish a known-good baseline: policy pinned, TTL 20, a restrictive allowlist.
        orgContext.runInOrg(orgA, () -> consoleSettings.update(policy, 20, "10.0.0.0/8"));

        // Now attempt to CLEAR the policy while supplying a malformed CIDR. The CIDR is validated after the
        // policy write, so only an atomic transaction prevents the clear from sticking.
        assertThatThrownBy(() -> orgContext.runInOrg(orgA, () -> consoleSettings.update(null, 15, "not-a-cidr")))
                .isInstanceOf(BadRequestException.class);

        assertThat(orgContext.callInOrg(orgA, () -> portals.sessionPolicyId(PortalApps.ADMIN)))
                .contains(policy); // the policy selection was NOT cleared — the failed update rolled back
        assertThat(orgContext.callInOrg(orgA, () -> consoleConfig.current()))
                .isEqualTo(new AdminConsoleConfigView(20, "10.0.0.0/8")); // config unchanged too
    }
}
