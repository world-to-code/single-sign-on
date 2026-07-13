package com.example.sso.portal;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.portal.binding.AdminConsoleConfigService;
import com.example.sso.portal.binding.AdminConsoleConfigView;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The admin console's elevation TTL + entry IP allowlist now live in a per-tenant overlay
 * ({@code admin_console_config}), not on a session policy. Verifies own-else-global resolution, per-tenant
 * isolation, that only the platform edits the GLOBAL default, and CIDR/TTL validation on write. The GLOBAL row
 * is migrated (V85) from the console's bound policy, so a fresh tenant inherits a real value.
 */
class AdminConsoleConfigIT extends AbstractIntegrationTest {

    @Autowired
    AdminConsoleConfigService consoleConfig;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;

    private UUID orgA;
    private UUID orgB;

    @AfterEach
    void tearDown() {
        // Deleting an org cascades its own config row. The GLOBAL row (migrated seed) is read but never mutated.
        if (orgA != null) {
            organizations.delete(orgA);
        }
        if (orgB != null) {
            organizations.delete(orgB);
        }
    }

    private UUID org() {
        String slug = "console-cfg-it-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug)).id();
    }

    private AdminConsoleConfigView globalConfig() {
        return orgContext.callAsPlatform(() -> consoleConfig.current());
    }

    private AdminConsoleConfigView configOf(UUID orgId) {
        return orgContext.callInOrg(orgId, () -> consoleConfig.current());
    }

    @Test
    void aFreshTenantInheritsTheMigratedGlobalConfig() {
        orgA = org();

        // Independent oracle: V85 must seed a real GLOBAL row (the historical default: 5 minutes, any network),
        // so an inheriting tenant never falls into a silent fail-open/lockout.
        assertThat(globalConfig().elevationTokenTtlMinutes()).isEqualTo(5);
        assertThat(globalConfig().adminAllowedCidrs()).isNull();
        assertThat(configOf(orgA)).isEqualTo(globalConfig()); // no own row -> inherits global
    }

    @Test
    void savingCreatesAnIsolatedTenantConfigLeavingOthersOnTheGlobal() {
        orgA = org();
        orgB = org();

        orgContext.runInOrg(orgA, () -> consoleConfig.update(30, "10.0.0.0/8"));

        assertThat(configOf(orgA)).isEqualTo(new AdminConsoleConfigView(30, "10.0.0.0/8"));
        assertThat(configOf(orgB)).isEqualTo(globalConfig()); // orgB untouched — still inherits the global default
    }

    @Test
    void onlyThePlatformContextMayWriteTheGlobalDefault() {
        // A bound-but-orgless, non-platform context must NOT fall through to rewriting the platform-wide default.
        assertThatThrownBy(() -> orgContext.runInOrg(null, () -> consoleConfig.update(10, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void thePlatformMayWriteAndRestoreTheGlobalDefault() {
        AdminConsoleConfigView original = globalConfig();
        try {
            orgContext.runAsPlatform(() -> consoleConfig.update(42, "192.168.0.0/16"));
            assertThat(globalConfig()).isEqualTo(new AdminConsoleConfigView(42, "192.168.0.0/16"));
        } finally {
            orgContext.runAsPlatform(() -> consoleConfig.update(original.elevationTokenTtlMinutes(),
                    original.adminAllowedCidrs()));
        }
        assertThat(globalConfig()).isEqualTo(original);
    }

    @Test
    void updateRejectsAnInvalidCidr() {
        orgA = org();
        assertThatThrownBy(() -> orgContext.runInOrg(orgA, () -> consoleConfig.update(10, "not-a-cidr")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void updateRejectsANonPositiveElevationTtl() {
        orgA = org();
        assertThatThrownBy(() -> orgContext.runInOrg(orgA, () -> consoleConfig.update(0, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void updateNormalizesTheAllowlistAndTreatsBlankAsAnyNetwork() {
        orgA = org();

        orgContext.runInOrg(orgA, () -> consoleConfig.update(15, " 10.0.0.0/8 , 203.0.113.0/24 "));
        assertThat(configOf(orgA).adminAllowedCidrs()).isEqualTo("10.0.0.0/8,203.0.113.0/24");

        assertThatCode(() -> orgContext.runInOrg(orgA, () -> consoleConfig.update(15, "   "))).doesNotThrowAnyException();
        assertThat(configOf(orgA).adminAllowedCidrs()).isNull(); // blank allowlist = any network
    }
}
