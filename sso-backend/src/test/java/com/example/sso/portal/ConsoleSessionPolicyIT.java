package com.example.sso.portal;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.binding.PortalSessionBinding;
import com.example.sso.session.policy.ConsoleSessionPolicy;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@link ConsoleSessionPolicy} governs the admin console's step-up. It must resolve the PORTAL/admin binding
 * scoped to the ACTING org, never the ambient platform context: an un-drilled super-admin resolves the GLOBAL
 * policy, never a tenant's binding (RLS under the platform GUC would otherwise expose every tenant's rows, and
 * org-ownership would rank a tenant row above the global pin).
 */
class ConsoleSessionPolicyIT extends AbstractIntegrationTest {

    @Autowired
    ConsoleSessionPolicy consolePolicy;
    @Autowired
    PortalSessionBinding portals;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;
    @Autowired
    SessionPolicyService sessionPolicies;
    @Autowired
    UserService users;

    private UUID orgA;
    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (orgA != null) {
            organizations.delete(orgA);
        }
        createdUsers.forEach(users::delete);
        createdUsers.clear();
    }

    private UUID defaultPolicyOf(UUID orgId) {
        return orgContext.callInOrg(orgId, () -> sessionPolicies.listAll().getFirst()).getId();
    }

    @Test
    void anUnDrilledPlatformConsoleResolvesTheGlobalPolicyNotATenantsBinding() {
        orgA = org();
        UUID policyOfA = defaultPolicyOf(orgA);
        orgContext.runInOrg(orgA, () -> portals.setSessionPolicy(PortalApps.ADMIN, policyOfA)); // orgA pins its own

        UUID globalPin = orgContext.callAsPlatform(() -> portals.sessionPolicyId(PortalApps.ADMIN)).orElseThrow();
        String superAdmin = "sa-" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = orgContext.callAsPlatform(() -> users.createUser(new NewUser(
                superAdmin, superAdmin + "@example.com", "SA", "S3cret!pw9", Set.of("ROLE_USER"))).getId());
        createdUsers.add(id);

        SessionPolicyDetails resolved = orgContext.callAsPlatform(() -> consolePolicy.resolveForConsole(superAdmin));
        assertThat(resolved.getId()).isNotEqualTo(policyOfA);   // orgA's tenant binding must NOT leak to the platform
        assertThat(resolved.getId()).isEqualTo(globalPin);      // the global pin governs the un-drilled super-admin
    }

    private UUID org() {
        // The org's baseline session policy is provisioned asynchronously after creation; block until it exists.
        UUID created = organizations.create(new NewOrganization(
                "console-pol-it-" + UUID.randomUUID().toString().substring(0, 8),
                "console-pol-it")).id();
        await().until(() -> orgContext.callInOrg(created, () -> !sessionPolicies.listAll().isEmpty()));
        return created;
    }
}
