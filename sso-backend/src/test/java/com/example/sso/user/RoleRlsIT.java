package com.example.sso.user;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the org-scoping RLS on the {@code role} table, verified through a dedicated <b>non-superuser</b>
 * role (a superuser — the Testcontainers/dev default — bypasses RLS). The role policy differs from the
 * membership one by an {@code org_id IS NULL} clause, so GLOBAL/system roles stay visible in EVERY context
 * (critical: login authority resolution and startup seeding read roles with no org bound):
 * <ul>
 *   <li>a tenant role is visible only in its org's context (or platform), never another org's nor unset;</li>
 *   <li>a GLOBAL role (org_id NULL) is visible in every context — org A, org B, platform, and unset;</li>
 *   <li>WITH CHECK: a global (NULL) role is writable in any context (seeder), a tenant role only in its own
 *       org's context; a cross-org write is refused.</li>
 * </ul>
 * Not {@code @Transactional} — each probe call is its own connection.
 */
class RoleRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void rlsIsolatesTenantRolesWhileKeepingGlobalRolesVisibleEverywhere() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("role-a");
        UUID orgB = newOrg("role-b");
        // Seed one role per tier via the owner connection (superuser bypasses RLS, so we control org_id).
        String globalName = "rls-global-" + s;
        String aName = "rls-a-" + s;
        String bName = "rls-b-" + s;
        seedRole(globalName, null);
        seedRole(aName, orgA);
        seedRole(bName, orgB);

        createProbeRole();
        try (Connection probe = DriverManager.getConnection(jdbcUrl(), "rls_probe", "probe")) {
            // Org A's context: sees the global role + A's role, never B's.
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, aName)).isTrue();
            assertThat(visible(probe, bName)).isFalse();

            // Org B's context: global + B's, never A's.
            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, bName)).isTrue();
            assertThat(visible(probe, aName)).isFalse();

            // No context (login authority resolution / seeder): ONLY global roles — tenant roles fail closed.
            resetContext(probe);
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, aName)).isFalse();
            assertThat(visible(probe, bName)).isFalse();

            // Platform: everything.
            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, aName)).isTrue();
            assertThat(visible(probe, bName)).isTrue();

            // WITH CHECK on writes:
            resetContext(probe);
            insertRole(probe, "rls-w-global-" + s, null);           // a GLOBAL role writable with no context (seeder)
            setContext(probe, "app.current_org", orgA.toString());
            insertRole(probe, "rls-w-a-" + s, orgA);                // A's own role in A's context — allowed
            assertThatThrownBy(() -> insertRole(probe, "rls-w-b-" + s, orgB)) // another org's role — refused
                    .isInstanceOf(SQLException.class);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> organizations.delete(id)); // FK cascade removes the org's roles
        return id;
    }

    /** Insert a role directly (owner connection bypasses RLS) so we control its tier. */
    private void seedRole(String name, UUID orgId) {
        jdbc.update("insert into role (id, name, org_id, system) values (gen_random_uuid(), ?, ?, false)",
                name, orgId);
        cleanups.add(() -> jdbc.update("delete from role where name = ?", name));
    }

    private String jdbcUrl() {
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getURL();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void createProbeRole() {
        dropProbeRole();
        jdbc.execute("CREATE ROLE rls_probe LOGIN PASSWORD 'probe' NOSUPERUSER");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO rls_probe");
        jdbc.execute("GRANT SELECT, INSERT, DELETE ON role TO rls_probe");
        cleanups.add(this::dropProbeRole);
    }

    private void dropProbeRole() {
        jdbc.execute("do $$ begin if exists (select from pg_roles where rolname = 'rls_probe') then "
                + "execute 'drop owned by rls_probe'; execute 'drop role rls_probe'; end if; end $$");
    }

    private void setContext(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config(?, ?, false)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.execute();
        }
    }

    private void resetContext(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("select set_config('app.current_org', '', false), set_config('app.platform', '', false)");
        }
    }

    private boolean visible(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from role where name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertRole(Connection c, String name, UUID orgId) throws SQLException {
        cleanups.add(() -> jdbc.update("delete from role where name = ?", name));
        try (PreparedStatement ps = c.prepareStatement(
                "insert into role (id, name, org_id, system) values (gen_random_uuid(), ?, ?, false)")) {
            ps.setString(1, name);
            ps.setObject(2, orgId);
            ps.executeUpdate();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
