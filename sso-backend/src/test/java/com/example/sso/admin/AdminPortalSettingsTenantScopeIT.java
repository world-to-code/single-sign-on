package com.example.sso.admin;

import com.example.sso.admin.internal.portalsettings.domain.AdminPortalSettingsRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant scoping of the admin-portal settings (the admin-console elevation policy). One row per org plus a
 * single GLOBAL default (org_id NULL, seeded in V25): a tenant inherits the global default until it saves
 * its own (copy-on-write), and one tenant's row never bleeds into another's or into the platform default.
 * The acting tenant is resolved from {@link OrgContext}; there is no RLS on the table, so this proves the
 * app-layer scoping for real.
 */
class AdminPortalSettingsTenantScopeIT extends AbstractIntegrationTest {

    @Autowired
    AdminPortalSettingsService settings;
    @Autowired
    OrgContext orgContext;
    @Autowired
    AdminPortalSettingsRepository repository;

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        // Remove only the per-tenant rows this test created; leave the global default row intact.
        repository.findByOrgId(orgA).ifPresent(repository::delete);
        repository.findByOrgId(orgB).ifPresent(repository::delete);
    }

    @Test
    void aTenantInheritsTheGlobalDefaultUntilItSaves() {
        AdminPortalSettingsData global = orgContext.callAsPlatform(settings::get);

        AdminPortalSettingsData asA = orgContext.callInOrg(orgA, settings::get);

        assertThat(asA.reauthIntervalMinutes()).isEqualTo(global.reauthIntervalMinutes());
        assertThat(asA.elevationTokenTtlMinutes()).isEqualTo(global.elevationTokenTtlMinutes());
        assertThat(repository.findByOrgId(orgA)).isEmpty(); // a pure read never materializes a row
    }

    @Test
    void savingCreatesAnIsolatedTenantRowThatLeavesGlobalAndOtherTenantsUntouched() {
        AdminPortalSettingsData global = orgContext.callAsPlatform(settings::get);

        orgContext.callInOrg(orgA, () -> settings.update(
                new AdminPortalSettingsData(42, 7, 21, 240, List.of("10.0.0.0/8"))));

        assertThat(orgContext.callInOrg(orgA, settings::get).reauthIntervalMinutes()).isEqualTo(42);
        assertThat(orgContext.callInOrg(orgA, settings::get).adminAllowedCidrs()).containsExactly("10.0.0.0/8");
        // orgB still inherits the untouched global default; the WHOLE global record is unchanged (not just one
        // field) — a bug bleeding orgA's CIDRs or TTL into the global row would be caught here.
        assertThat(orgContext.callInOrg(orgB, settings::get)).isEqualTo(global);
        assertThat(orgContext.callAsPlatform(settings::get)).isEqualTo(global);
    }

    @Test
    void updateRejectsAnInvalidCidr() {
        assertThatThrownBy(() -> orgContext.callInOrg(orgA, () -> settings.update(
                new AdminPortalSettingsData(10, 5, 30, 480, List.of("not-a-cidr")))))
                .isInstanceOf(BadRequestException.class);
        assertThat(repository.findByOrgId(orgA)).isEmpty(); // the rejected write materialized no row
    }

    @Test
    void twoTenantsKeepSeparateSettings() {
        orgContext.callInOrg(orgA, () -> settings.update(
                new AdminPortalSettingsData(11, 3, 15, 120, List.of())));
        orgContext.callInOrg(orgB, () -> settings.update(
                new AdminPortalSettingsData(99, 9, 45, 600, List.of())));

        assertThat(orgContext.callInOrg(orgA, settings::get).reauthIntervalMinutes()).isEqualTo(11);
        assertThat(orgContext.callInOrg(orgB, settings::get).reauthIntervalMinutes()).isEqualTo(99);
    }

    @Test
    void onlyThePlatformContextMayWriteTheGlobalDefault() {
        // A bound-but-orgless, non-platform context (an org user that authenticated without a resolved tenant)
        // must NOT fall through to rewriting the platform-wide default every tenant inherits.
        assertThatThrownBy(() -> orgContext.callInOrg(null, () -> settings.update(
                new AdminPortalSettingsData(1, 1, 1, 1, List.of()))))
                .isInstanceOf(ForbiddenException.class);

        // The platform super-admin context may edit it (write back the current values so the shared row is
        // left byte-for-byte unchanged for other tests).
        AdminPortalSettingsData global = orgContext.callAsPlatform(settings::get);
        orgContext.callAsPlatform(() -> settings.update(new AdminPortalSettingsData(
                global.reauthIntervalMinutes(), global.elevationTokenTtlMinutes(),
                global.sessionIdleTimeoutMinutes(), global.sessionAbsoluteLifetimeMinutes(),
                global.adminAllowedCidrs())));
        assertThat(orgContext.callAsPlatform(settings::get).reauthIntervalMinutes())
                .isEqualTo(global.reauthIntervalMinutes());
    }
}
