package com.example.sso.admin;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.portal.binding.AdminConsoleBinding;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.SessionPolicySpec;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Tenant scoping of the admin console's governing session policy, now stored as a {@code PORTAL}/{@code admin}
 * all-subjects binding in {@code policy_binding}. A tenant with no binding defaults to its admin's own resolved
 * policy; a tenant's selection is org-owned and never bleeds into another's or into the platform default; a
 * tenant may select only a policy of its OWN tier; and a policy governing a console cannot be deleted until it
 * is deselected (the binding's ON DELETE RESTRICT).
 */
class AdminPortalSettingsTenantScopeIT extends AbstractIntegrationTest {

    @Autowired
    AdminConsoleBinding consoleBinding;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;
    @Autowired
    SessionPolicyService sessionPolicies;

    private UUID orgA;
    private UUID orgB;

    @AfterEach
    void tearDown() {
        // Deleting the org cascades its own bindings/policies. The GLOBAL console binding (migrated seed data
        // every tenant inherits) is left untouched — tests read it but must never mutate or delete it.
        if (orgA != null) {
            organizations.delete(orgA);
        }
        if (orgB != null) {
            organizations.delete(orgB);
        }
    }

    private Optional<UUID> globalConsolePolicy() {
        return orgContext.callAsPlatform(() -> consoleBinding.sessionPolicyId());
    }

    private UUID org() {
        String slug = "portal-it-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug)).id();
    }

    /** The org's own Default policy, once the (async) tenant baseline provisioning has created it. */
    private UUID defaultPolicyOf(UUID orgId) {
        await().until(() -> orgContext.callInOrg(orgId, () -> !sessionPolicies.listAll().isEmpty()));
        return orgContext.callInOrg(orgId, () -> sessionPolicies.listAll().getFirst()).getId();
    }

    private Optional<UUID> consolePolicyOf(UUID orgId) {
        return orgContext.callInOrg(orgId, () -> consoleBinding.sessionPolicyId());
    }

    private int bindingRows(UUID orgId) {
        return ownerJdbc().queryForObject(
                "select count(*) from policy_binding where app_type = 'PORTAL' and app_id = 'admin' and org_id = ?",
                Integer.class, orgId);
    }

    @Test
    void aFreshTenantInheritsTheGlobalConsolePolicy() {
        orgA = org();

        assertThat(consolePolicyOf(orgA)).isEqualTo(globalConsolePolicy()); // no own binding → inherits global
        assertThat(bindingRows(orgA)).isZero();                             // a pure read materializes no row
    }

    @Test
    void savingCreatesAnIsolatedTenantBindingThatLeavesOtherTenantsUntouched() {
        orgA = org();
        orgB = org();
        UUID policyOfA = defaultPolicyOf(orgA);

        orgContext.runInOrg(orgA, () -> consoleBinding.setSessionPolicy(policyOfA));

        assertThat(consolePolicyOf(orgA)).contains(policyOfA);
        assertThat(consolePolicyOf(orgB)).isEqualTo(globalConsolePolicy()); // orgB untouched — still inherits global
        assertThat(bindingRows(orgB)).isZero();
    }

    @Test
    void aTenantMayNotSelectAPolicyOutsideItsOwnTier() {
        orgA = org();
        orgB = org();
        UUID policyOfB = defaultPolicyOf(orgB);

        // Pointing A's console at B's policy would govern A with a posture A neither owns nor can inspect.
        assertThatThrownBy(() -> orgContext.runInOrg(orgA, () -> consoleBinding.setSessionPolicy(policyOfB)))
                .isInstanceOf(BadRequestException.class);
        assertThat(bindingRows(orgA)).isZero(); // the rejected write materialized no row
    }

    @Test
    void updateRejectsAnUnknownPolicy() {
        orgA = org();
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> orgContext.runInOrg(orgA, () -> consoleBinding.setSessionPolicy(unknown)))
                .isInstanceOf(BadRequestException.class);
        assertThat(bindingRows(orgA)).isZero();
    }

    @Test
    void clearingTheSelectionReturnsToTheInheritedGlobal() {
        orgA = org();
        UUID policyOfA = defaultPolicyOf(orgA);
        orgContext.runInOrg(orgA, () -> consoleBinding.setSessionPolicy(policyOfA));

        orgContext.runInOrg(orgA, () -> consoleBinding.setSessionPolicy(null));

        assertThat(consolePolicyOf(orgA)).isEqualTo(globalConsolePolicy()); // own binding gone — back to global
        assertThat(bindingRows(orgA)).isZero();
    }

    @Test
    void twoTenantsKeepSeparateSelections() {
        orgA = org();
        orgB = org();
        UUID policyOfA = defaultPolicyOf(orgA);
        UUID policyOfB = defaultPolicyOf(orgB);

        orgContext.runInOrg(orgA, () -> consoleBinding.setSessionPolicy(policyOfA));
        orgContext.runInOrg(orgB, () -> consoleBinding.setSessionPolicy(policyOfB));

        assertThat(consolePolicyOf(orgA)).contains(policyOfA);
        assertThat(consolePolicyOf(orgB)).contains(policyOfB);
    }

    @Test
    void onlyThePlatformContextMayWriteTheGlobalDefault() {
        // A bound-but-orgless, non-platform context (an org user that authenticated without a resolved tenant)
        // must NOT fall through to rewriting the platform-wide default.
        assertThatThrownBy(() -> orgContext.runInOrg(null, () -> consoleBinding.setSessionPolicy(null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aPolicyGoverningTheConsoleCannotBeDeletedUntilItIsDeselected() {
        orgA = org();
        UUID custom = orgContext.callInOrg(orgA, () -> sessionPolicies.create(
                new SessionPolicySpec("Console-" + UUID.randomUUID().toString().substring(0, 8), 20, true,
                        480, 30, 5, "TOTP", 2, "TOTP", true, 0, true, "Lax", 5, "10.0.0.0/8",
                        Set.of(), Set.of(), List.of()))).getId();
        orgContext.runInOrg(orgA, () -> consoleBinding.setSessionPolicy(custom));

        // The binding's ON DELETE RESTRICT refuses the delete (it would silently drop that console posture).
        assertThatThrownBy(() -> orgContext.runInOrg(orgA, () -> sessionPolicies.delete(custom)))
                .isInstanceOf(ConflictException.class);
        assertThat(consolePolicyOf(orgA)).contains(custom);

        orgContext.runInOrg(orgA, () -> consoleBinding.setSessionPolicy(null));
        assertThatCode(() -> orgContext.runInOrg(orgA, () -> sessionPolicies.delete(custom)))
                .doesNotThrowAnyException();
    }
}
