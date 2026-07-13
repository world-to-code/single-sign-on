package com.example.sso.session;

import com.example.sso.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the hazard every data migration touching a {@code FORCE ROW LEVEL SECURITY} table must handle: a
 * migration running as a NON-superuser owner (the hardened prod setup) sees only GLOBAL rows unless it sets
 * {@code app.platform}. A backfill written without that guard silently skips every tenant row — and no test
 * catches it, because Testcontainers migrates as a superuser.
 *
 * <p>V85 (which relocated each tenant's admin-console TTL/CIDRs off {@code session_policy} into the FORCE-RLS
 * {@code admin_console_config} overlay) is exactly such a backfill; it opens with {@code SET LOCAL app.platform
 * = 'on'}. This test proves that guard is the correct mechanism: without it a tenant row is invisible and
 * un-updatable to a non-superuser role, with it both work.
 */
class SessionPolicyBackfillRlsIT extends AbstractIntegrationTest {

    private final UUID orgId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        ownerJdbc().update("delete from admin_console_config where org_id = ?", orgId);
        ownerJdbc().update("delete from organization where id = ?", orgId);
    }

    /** Seeds a tenant-owned console config via the privileged owner (a non-superuser could not insert it). */
    private void seedTenantConfig() {
        ownerJdbc().update("insert into organization (id, slug, name, status, created_at) "
                + "values (?, ?, ?, 'ACTIVE', now())", orgId, "rls-backfill-it", "RLS Backfill IT");
        ownerJdbc().update("insert into admin_console_config (org_id, elevation_token_ttl_minutes, admin_allowed_cidrs) "
                + "values (?, 5, null)", orgId);
    }

    @Test
    void aNonSuperuserBackfillMissesTenantRowsWithoutThePlatformGuc() throws SQLException {
        seedTenantConfig();

        try (Connection migration = appRoleConnection()) {
            // No app.platform: RLS collapses the USING clause to `org_id IS NULL` — the tenant row is invisible,
            // so an UPDATE ... WHERE (no org predicate) touches ZERO tenant rows and reports success.
            assertThat(updateAdminCidrs(migration, "10.0.0.0/8")).isZero();
            assertThat(readAdminCidrsAsOwner()).isNull();
        }
    }

    @Test
    void thePlatformGucLetsTheBackfillReachEveryTenantRow() throws SQLException {
        seedTenantConfig();

        try (Connection migration = appRoleConnection()) {
            migration.setAutoCommit(false);
            try (Statement st = migration.createStatement()) {
                st.execute("set local app.platform = 'on'"); // exactly what V85 opens with
            }
            assertThat(updateAdminCidrs(migration, "10.0.0.0/8")).isEqualTo(1);
            migration.commit();
        }

        assertThat(readAdminCidrsAsOwner()).isEqualTo("10.0.0.0/8");
    }

    /**
     * The shape of V85's backfill: an UPDATE that reaches tenant rows only through RLS visibility. Scoped to
     * this test's own org so it never mutates a sibling test's config.
     */
    private int updateAdminCidrs(Connection connection, String cidrs) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "update admin_console_config set admin_allowed_cidrs = ? where org_id = ?")) {
            ps.setString(1, cidrs);
            ps.setObject(2, orgId);
            return ps.executeUpdate();
        }
    }

    private String readAdminCidrsAsOwner() {
        return ownerJdbc().queryForObject(
                "select admin_allowed_cidrs from admin_console_config where org_id = ?", String.class, orgId);
    }
}
