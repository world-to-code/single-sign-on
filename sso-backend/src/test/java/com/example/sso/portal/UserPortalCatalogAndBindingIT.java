package com.example.sso.portal;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.binding.PortalSessionBinding;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The end-user portal is now a first-class catalog application ({@code PORTAL}/{@code user}) that carries its
 * own session policy through a policy binding, independent of the admin console's binding. Verifies it appears
 * in the catalog for every tier and that its per-tenant session selection is isolated from the admin console's.
 */
class UserPortalCatalogAndBindingIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationService applications;
    @Autowired
    PortalSessionBinding portals;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;
    @Autowired
    SessionPolicyService sessionPolicies;

    private UUID orgA;

    @AfterEach
    void tearDown() {
        if (orgA != null) {
            organizations.delete(orgA);
        }
    }

    private UUID org() {
        String slug = "userportal-it-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug)).id();
    }

    private UUID defaultPolicyOf(UUID orgId) {
        await().until(() -> orgContext.callInOrg(orgId, () -> !sessionPolicies.listAll().isEmpty()));
        return orgContext.callInOrg(orgId, () -> sessionPolicies.listAll().getFirst()).getId();
    }

    @Test
    void theUserPortalIsPublishedInEveryTiersCatalog() {
        orgA = org();

        assertThat(orgContext.callAsPlatform(() -> applications.listApplications())).anySatisfy(this::isUserPortal);
        assertThat(orgContext.callInOrg(orgA, () -> applications.listApplications())).anySatisfy(this::isUserPortal);
    }

    private void isUserPortal(ApplicationView app) {
        assertThat(app.type()).isEqualTo("PORTAL");
        assertThat(app.id()).isEqualTo(PortalApps.USER);
        assertThat(app.system()).isTrue();
    }

    @Test
    void theUserPortalSessionSelectionIsIsolatedFromTheAdminConsoleBinding() {
        orgA = org();
        UUID policy = defaultPolicyOf(orgA);

        orgContext.runInOrg(orgA, () -> portals.setSessionPolicy(PortalApps.USER, policy));

        assertThat(orgContext.callInOrg(orgA, () -> portals.sessionPolicyId(PortalApps.USER))).contains(policy);
        // Setting the USER portal must not touch the ADMIN console binding — they are separate PORTAL app rows.
        assertThat(orgContext.callInOrg(orgA, () -> portals.sessionPolicyId(PortalApps.ADMIN)))
                .isEqualTo(orgContext.callAsPlatform(() -> portals.sessionPolicyId(PortalApps.ADMIN))); // still the inherited global
    }

    @Test
    void clearingTheUserPortalSelectionRemovesTheTenantBinding() {
        orgA = org();
        UUID policy = defaultPolicyOf(orgA);
        orgContext.runInOrg(orgA, () -> portals.setSessionPolicy(PortalApps.USER, policy));

        orgContext.runInOrg(orgA, () -> portals.setSessionPolicy(PortalApps.USER, null));

        assertThat(orgContext.callInOrg(orgA, () -> portals.sessionPolicyId(PortalApps.USER)))
                .isEqualTo(orgContext.callAsPlatform(() -> portals.sessionPolicyId(PortalApps.USER))); // back to inherited global
    }
}
