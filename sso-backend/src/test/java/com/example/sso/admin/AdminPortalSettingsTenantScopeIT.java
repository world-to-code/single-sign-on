package com.example.sso.admin;

import com.example.sso.admin.internal.portalsettings.domain.AdminPortalSettingsRepository;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
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
 * Tenant scoping of the admin-portal settings — now a single choice: WHICH session policy governs the admin
 * console. One row per org plus a single GLOBAL default (org_id NULL, seeded in V25): a tenant inherits the
 * global default until it saves its own (copy-on-write), and one tenant's selection never bleeds into
 * another's or into the platform default. The acting tenant is resolved from {@link OrgContext}; there is no
 * RLS on the table, so this proves the app-layer scoping for real. A tenant may only select a policy of its
 * OWN tier.
 */
class AdminPortalSettingsTenantScopeIT extends AbstractIntegrationTest {

    @Autowired
    AdminPortalSettingsService settings;
    @Autowired
    OrgContext orgContext;
    @Autowired
    AdminPortalSettingsRepository repository;
    @Autowired
    OrganizationService organizations;
    @Autowired
    SessionPolicyService sessionPolicies;

    private UUID orgA;
    private UUID orgB;

    @AfterEach
    void tearDown() {
        // Remove only the per-tenant rows this test created; leave the global default row intact.
        if (orgA != null) {
            repository.findByOrgId(orgA).ifPresent(repository::delete);
            organizations.delete(orgA);
        }
        if (orgB != null) {
            repository.findByOrgId(orgB).ifPresent(repository::delete);
            organizations.delete(orgB);
        }
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

    @Test
    void aTenantInheritsTheGlobalDefaultUntilItSaves() {
        orgA = org();
        AdminPortalSettingsData global = orgContext.callAsPlatform(settings::get);

        AdminPortalSettingsData asA = orgContext.callInOrg(orgA, settings::get);

        assertThat(asA.sessionPolicyId()).isEqualTo(global.sessionPolicyId());
        assertThat(repository.findByOrgId(orgA)).isEmpty(); // a pure read never materializes a row
    }

    @Test
    void savingCreatesAnIsolatedTenantRowThatLeavesGlobalAndOtherTenantsUntouched() {
        orgA = org();
        orgB = org();
        AdminPortalSettingsData global = orgContext.callAsPlatform(settings::get);
        UUID policyOfA = defaultPolicyOf(orgA);

        orgContext.callInOrg(orgA, () -> settings.update(new AdminPortalSettingsData(policyOfA)));

        assertThat(orgContext.callInOrg(orgA, settings::get).sessionPolicyId()).isEqualTo(policyOfA);
        // orgB still inherits the untouched global default; the global record is unchanged.
        assertThat(orgContext.callInOrg(orgB, settings::get)).isEqualTo(global);
        assertThat(orgContext.callAsPlatform(settings::get)).isEqualTo(global);
    }

    @Test
    void aTenantMayNotSelectAPolicyOutsideItsOwnTier() {
        orgA = org();
        orgB = org();
        UUID policyOfB = defaultPolicyOf(orgB);

        // Pointing A's console at B's policy would govern A with a posture A neither owns nor can inspect.
        assertThatThrownBy(() -> orgContext.callInOrg(orgA,
                () -> settings.update(new AdminPortalSettingsData(policyOfB))))
                .isInstanceOf(BadRequestException.class);
        assertThat(repository.findByOrgId(orgA)).isEmpty(); // the rejected write materialized no row
    }

    @Test
    void updateRejectsAnUnknownPolicy() {
        orgA = org();

        assertThatThrownBy(() -> orgContext.callInOrg(orgA,
                () -> settings.update(new AdminPortalSettingsData(UUID.randomUUID()))))
                .isInstanceOf(BadRequestException.class);
        assertThat(repository.findByOrgId(orgA)).isEmpty();
    }

    @Test
    void clearingTheSelectionIsAlwaysAllowed() {
        orgA = org();
        UUID policyOfA = defaultPolicyOf(orgA);
        orgContext.callInOrg(orgA, () -> settings.update(new AdminPortalSettingsData(policyOfA)));

        orgContext.callInOrg(orgA, () -> settings.update(new AdminPortalSettingsData(null)));

        // Cleared: the console falls back to the policy resolved for the acting admin.
        assertThat(orgContext.callInOrg(orgA, settings::get).sessionPolicyId()).isNull();
    }

    @Test
    void twoTenantsKeepSeparateSelections() {
        orgA = org();
        orgB = org();
        UUID policyOfA = defaultPolicyOf(orgA);
        UUID policyOfB = defaultPolicyOf(orgB);

        orgContext.callInOrg(orgA, () -> settings.update(new AdminPortalSettingsData(policyOfA)));
        orgContext.callInOrg(orgB, () -> settings.update(new AdminPortalSettingsData(policyOfB)));

        assertThat(orgContext.callInOrg(orgA, settings::get).sessionPolicyId()).isEqualTo(policyOfA);
        assertThat(orgContext.callInOrg(orgB, settings::get).sessionPolicyId()).isEqualTo(policyOfB);
    }

    @Test
    void onlyThePlatformContextMayWriteTheGlobalDefault() {
        // A bound-but-orgless, non-platform context (an org user that authenticated without a resolved tenant)
        // must NOT fall through to rewriting the platform-wide default every tenant inherits.
        assertThatThrownBy(() -> orgContext.callInOrg(null,
                () -> settings.update(new AdminPortalSettingsData(null))))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void theGlobalDefaultIsSelectableByThePlatform() {
        SessionPolicyDetails globalDefault = orgContext.callAsPlatform(sessionPolicies::defaultPolicy);

        orgContext.callAsPlatform(() -> settings.update(new AdminPortalSettingsData(globalDefault.getId())));

        assertThat(orgContext.callAsPlatform(settings::get).sessionPolicyId()).isEqualTo(globalDefault.getId());
        orgContext.callAsPlatform(() -> settings.update(new AdminPortalSettingsData(null))); // restore
    }
}
