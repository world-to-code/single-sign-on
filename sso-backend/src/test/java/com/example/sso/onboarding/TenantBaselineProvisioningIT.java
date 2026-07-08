package com.example.sso.onboarding;

import com.example.sso.authpolicy.AuthPolicyAdminService;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.organization.CompanyProfile;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creating an organization provisions that tenant's OWN baseline policies (via OrganizationCreatedEvent →
 * TenantBaselineProvisioner, AFTER_COMMIT). The tenant admin must therefore find real, editable, org-owned
 * "Default" session and auth policies — not an empty page — and those must WIN resolution for the tenant over
 * the global fallback. Runs against Testcontainers so the RLS-scoped writes + resolution are exercised.
 */
class TenantBaselineProvisioningIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;
    @Autowired
    SessionPolicyService sessionPolicies;
    @Autowired
    AuthPolicyAdminService authPolicies;
    @Autowired
    UserService users;
    @Autowired
    OrgContext orgContext;

    private UUID orgId;
    private UUID userId;

    @AfterEach
    void tearDown() {
        if (userId != null) {
            users.delete(userId);
        }
        if (orgId != null) {
            organizations.delete(orgId); // ON DELETE CASCADE removes the provisioned session + auth policies
        }
    }

    @Test
    void creatingAnOrganizationProvisionsItsOwnEditableDefaultPolicies() {
        OrganizationView org = organizations.create(
                new NewOrganization("octatco-baseline-it", "Octatco Baseline IT", CompanyProfile.empty()));
        orgId = org.id();

        // The tenant admin's policy pages (tier-scoped listAll) show a real, org-owned "Default" for both axes.
        orgContext.runInOrg(orgId, () -> {
            assertThat(sessionPolicies.listAll())
                    .anyMatch(p -> SessionPolicyService.DEFAULT_NAME.equals(p.getName())
                            && p.getPriority() == SessionPolicyService.TENANT_DEFAULT_PRIORITY && p.isEnabled());
            assertThat(authPolicies.listAll())
                    .anyMatch(p -> "Default".equals(p.getName())
                            && p.getPriority() == AuthPolicyAdminService.TENANT_DEFAULT_PRIORITY);
        });
    }

    @Test
    void theTenantsOwnSessionPolicyWinsResolutionOverTheGlobalFallback() {
        OrganizationView org = organizations.create(
                new NewOrganization("octatco-resolve-it", "Octatco Resolve IT", CompanyProfile.empty()));
        orgId = org.id();
        UserAccount member = users.createUser(
                new NewUser("member@octatco-resolve-it.test", "member@octatco-resolve-it.test",
                        "Member", "S3cret!pw9", Set.of("ROLE_USER")), orgId);
        userId = member.getId();

        SessionPolicyDetails resolved = orgContext.callInOrg(orgId, () -> sessionPolicies.resolveForUser(member));

        // The per-org Default (priority 1) beats the GLOBAL Default (priority 0), so the tenant runs on its own.
        assertThat(resolved.getName()).isEqualTo(SessionPolicyService.DEFAULT_NAME);
        assertThat(resolved.getPriority()).isEqualTo(SessionPolicyService.TENANT_DEFAULT_PRIORITY);
    }
}
