package com.example.sso.session;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the org-scoping RLS on {@code session_policy} and {@code network_zone} against the application's real
 * NON-SUPERUSER runtime role ({@code sso_app}) — a superuser (the Testcontainers/dev default) bypasses RLS.
 * Both tables use the global-default + org-override policy (mirrors {@code role}): a tenant row is visible only
 * in its org's context (or platform), a GLOBAL row (org_id NULL — the seeded Default session policy,
 * platform-wide zones) is visible in every context including unset (startup seeding / the request path's
 * cross-org cache load), and WITH CHECK refuses writing another org's row from a tenant context.
 * Seeding/teardown use the privileged owner connection ({@link #ownerJdbc()}); the isolation assertions use a
 * raw {@link #appRoleConnection()}. Not {@code @Transactional} — each probe call is its own connection.
 */
class SessionPolicyAndZoneRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    // session_policy.priority is UNIQUE per tier (V90); hand each seeded/inserted session policy a distinct one
    // (base 100 avoids the seeded Defaults at 0/1). network_zone has no priority column, so it is untouched.
    private int nextPriority = 100;

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void rlsIsolatesTenantSessionPolicies() throws SQLException {
        assertRlsIsolates("session_policy", "spol");
    }

    @Test
    void rlsIsolatesTenantNetworkZones() throws SQLException {
        assertRlsIsolates("network_zone", "nzone");
    }

    private void assertRlsIsolates(String table, String slugPrefix) throws SQLException {
        String s = suffix();
        UUID orgA = newOrg(slugPrefix + "-a");
        UUID orgB = newOrg(slugPrefix + "-b");
        String globalName = "rls-global-" + s;
        String aName = "rls-a-" + s;
        String bName = "rls-b-" + s;
        seedRow(table, globalName, null);
        seedRow(table, aName, orgA);
        seedRow(table, bName, orgB);

        try (Connection probe = appRoleConnection()) {
            // Org A's context: sees the global row + A's row, never B's.
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, table, globalName)).isTrue();
            assertThat(visible(probe, table, aName)).isTrue();
            assertThat(visible(probe, table, bName)).isFalse();

            // Org B's context: global + B's, never A's.
            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, table, globalName)).isTrue();
            assertThat(visible(probe, table, bName)).isTrue();
            assertThat(visible(probe, table, aName)).isFalse();

            // No context (startup / the platform cache load runs with platform on, but an unset probe sees
            // ONLY global rows — tenant rows fail closed).
            resetContext(probe);
            assertThat(visible(probe, table, globalName)).isTrue();
            assertThat(visible(probe, table, aName)).isFalse();
            assertThat(visible(probe, table, bName)).isFalse();

            // Platform: everything (this is the context the in-memory cache is (re)loaded under).
            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, table, globalName)).isTrue();
            assertThat(visible(probe, table, aName)).isTrue();
            assertThat(visible(probe, table, bName)).isTrue();

            // WITH CHECK on writes (tighter than the read side):
            resetContext(probe);
            insertRow(probe, table, "rls-w-global-" + s, null);          // a GLOBAL row writable ONLY with no context (seeder)
            setContext(probe, "app.current_org", orgA.toString());
            insertRow(probe, table, "rls-w-a-" + s, orgA);               // A's own row in A's context — allowed
            assertThatThrownBy(() -> insertRow(probe, table, "rls-w-b-" + s, orgB)) // another org's row — refused
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertRow(probe, table, "rls-w-global2-" + s, null)) // a GLOBAL row from a
                    .isInstanceOf(SQLException.class);                   // tenant-bound context — refused by WITH CHECK
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        // Delete via the owner: an org's cascade-deletes hit RLS-guarded child rows the app role cannot see.
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    /** Insert a row directly (owner connection bypasses RLS) so we control its tier. */
    private void seedRow(String table, String name, UUID orgId) {
        if (table.equals("session_policy")) {
            ownerJdbc().update("insert into session_policy (id, name, org_id, priority) "
                    + "values (gen_random_uuid(), ?, ?, ?)", name, orgId, nextPriority++);
        } else {
            ownerJdbc().update("insert into " + table + " (id, name, org_id) values (gen_random_uuid(), ?, ?)",
                    name, orgId);
        }
        cleanups.add(() -> ownerJdbc().update("delete from " + table + " where name = ?", name));
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

    private boolean visible(Connection c, String table, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from " + table + " where name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertRow(Connection c, String table, String name, UUID orgId) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from " + table + " where name = ?", name));
        boolean sessionPolicy = table.equals("session_policy");
        String sql = sessionPolicy
                ? "insert into session_policy (id, name, org_id, priority) values (gen_random_uuid(), ?, ?, ?)"
                : "insert into " + table + " (id, name, org_id) values (gen_random_uuid(), ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setObject(2, orgId);
            if (sessionPolicy) {
                ps.setInt(3, nextPriority++);
            }
            ps.executeUpdate();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
