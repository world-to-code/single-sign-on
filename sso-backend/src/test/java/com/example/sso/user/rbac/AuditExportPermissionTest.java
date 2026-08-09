package com.example.sso.user.rbac;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code audit:export} is, and — more to the point — what it is not.
 *
 * <p>The exporter reads across EVERY tenant, because a SIEM ingests the whole deployment. {@code audit:read}
 * is deliberately the opposite: it resolves the acting tenant and shows an administrator only their own org.
 * Sharing one permission between the two would make a tenant-grantable permission imply cross-tenant reach,
 * which is the exact shape the tenant-grantable rule exists to refuse.
 *
 * <p>The implication direction is the subtle half. {@code expandImplied} widens any two-segment mutating
 * permission to {@code <resource>:read}, and {@code audit:read} is itself a macro over every category plus
 * actor PII — so left alone, granting "may ship logs to the collector" would quietly hand out the entire
 * audit console, PII included.
 */
class AuditExportPermissionTest {

    @Test
    void exportIsPlatformOnly() {
        assertThat(Permissions.isPlatform(Permissions.AUDIT_EXPORT)).isTrue();
        assertThat(Permissions.tenantGrantable()).doesNotContain(Permissions.AUDIT_EXPORT);
    }

    /** The tenant-scoped read must stay grantable — this is about export, not about locking the console down. */
    @Test
    void theTenantScopedAuditReadIsStillGrantable() {
        assertThat(Permissions.tenantGrantable()).contains(Permissions.AUDIT_READ);
    }

    @Test
    void holdingExportDoesNotHandOutTheAuditConsole() {
        Set<String> effective = Permissions.expandImplied(Set.of(Permissions.AUDIT_EXPORT));

        assertThat(effective)
                .as("export must not widen into the tenant-scoped read, nor into its category/PII macro")
                .containsExactly(Permissions.AUDIT_EXPORT);
    }

    /** And not the reverse either: reading the console is not authority to ship it somewhere else. */
    @Test
    void holdingTheAuditReadDoesNotImplyExport() {
        assertThat(Permissions.expandImplied(Set.of(Permissions.AUDIT_READ)))
                .doesNotContain(Permissions.AUDIT_EXPORT);
    }

    @Test
    void exportIsInTheCatalog() {
        assertThat(Permissions.ALL).contains(Permissions.AUDIT_EXPORT);
    }
}
