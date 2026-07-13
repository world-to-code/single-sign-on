package com.example.sso.onboarding;

import com.example.sso.authpolicy.policy.AuthPolicyAdminService;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.organization.CompanyProfile;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.session.policy.SessionAssignment;
import com.example.sso.session.policy.SessionBindings;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.SessionPolicyUpdate;
import com.example.sso.session.policy.UserSessionPolicy;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Creating an organization provisions that tenant's OWN baseline policies (via OrganizationCreatedEvent →
 * TenantBaselineProvisioner, AFTER_COMMIT + async, hence the awaits). The tenant admin must therefore find
 * real, editable, org-owned "Default" session and auth policies — not an empty page — and those must WIN
 * resolution for the tenant over the global fallback. Runs against Testcontainers so the RLS-scoped writes
 * + resolution are exercised.
 */
class TenantBaselineProvisioningIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;
    @Autowired
    SessionPolicyService sessionPolicies;
    @Autowired
    UserSessionPolicy userSessionPolicy;
    @Autowired
    SessionBindings sessionBindings;
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
        await().untilAsserted(() -> orgContext.runInOrg(orgId, () -> {
            assertThat(sessionPolicies.listAll())
                    .anyMatch(p -> SessionPolicyService.DEFAULT_NAME.equals(p.getName())
                            && p.getPriority() == SessionPolicyService.TENANT_DEFAULT_PRIORITY && p.isEnabled());
            assertThat(authPolicies.listAll())
                    .anyMatch(p -> "Default".equals(p.getName())
                            && p.getPriority() == AuthPolicyAdminService.TENANT_DEFAULT_PRIORITY);
        }));
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

        // The per-org Default (priority 1) beats the GLOBAL Default (priority 0), so the tenant runs on its own.
        await().untilAsserted(() -> {
            SessionPolicyDetails resolved =
                    orgContext.callInOrg(orgId, () -> userSessionPolicy.resolveForUser(member));
            assertThat(resolved.getName()).isEqualTo(SessionPolicyService.DEFAULT_NAME);
            assertThat(resolved.getPriority()).isEqualTo(SessionPolicyService.TENANT_DEFAULT_PRIORITY);
        });
    }

    @Test
    void theProvisionedDefaultStaysTheUnconditionalFallbackAndCannotBeReassignedOrDeleted() {
        OrganizationView org = organizations.create(
                new NewOrganization("octatco-lock-it", "Octatco Lock IT", CompanyProfile.empty()));
        orgId = org.id();

        // Wait for the async baseline provisioning before mutating the Default it creates.
        await().untilAsserted(() -> orgContext.runInOrg(orgId, () ->
                assertThat(sessionPolicies.listAll())
                        .anyMatch(p -> SessionPolicyService.DEFAULT_NAME.equals(p.getName()))));

        orgContext.runInOrg(orgId, () -> {
            SessionPolicyDetails def = theDefault();
            // Try to target the Default at a specific role AND raise its priority — the server must refuse both,
            // so the fallback can never be stranded on an empty/narrow set.
            sessionPolicies.update(def.getId(), reassign(def, UUID.randomUUID(), 99));

            SessionPolicyDetails after = theDefault();
            // Reassignment refused: the Default stays an all-subjects binding (no per-subject scope in the matrix).
            SessionAssignment scope = sessionBindings.describe(List.of(after.getId())).get(after.getId());
            assertThat(scope.roleIds()).isEmpty();
            assertThat(scope.userIds()).isEmpty();
            assertThat(after.getPriority()).isEqualTo(SessionPolicyService.TENANT_DEFAULT_PRIORITY);
            assertThat(after.isEnabled()).isTrue();

            // And it cannot be deleted (it is the tier's required fallback).
            assertThatThrownBy(() -> sessionPolicies.delete(def.getId())).isInstanceOf(BadRequestException.class);
        });
    }

    private SessionPolicyDetails theDefault() {
        return sessionPolicies.listAll().stream()
                .filter(p -> SessionPolicyService.DEFAULT_NAME.equals(p.getName()))
                .findFirst().orElseThrow();
    }

    /** A well-formed update that attempts to assign the policy to {@code roleId} and change its priority. */
    private SessionPolicyUpdate reassign(SessionPolicyDetails p, UUID roleId, int priority) {
        return new SessionPolicyUpdate(priority, false, p.getAbsoluteTimeoutMinutes(), p.getIdleTimeoutMinutes(),
                p.getReauthIntervalMinutes(), p.getReauthFactors(), p.getSensitiveReauthWindowMinutes(),
                p.getStepUpFactors(), p.isBindClient(), p.getMaxConcurrentSessions(), p.isRotateOnReauth(),
                p.getCookieSameSite(),
                Set.of(), Set.of(roleId), List.of());
    }
}
