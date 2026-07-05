package com.example.sso.crypto;

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
 * Proves the org-scoping RLS on {@code signing_key} (which holds tenant private keys, encrypted at rest),
 * verified through a dedicated <b>non-superuser</b> role (a superuser bypasses RLS). Same global-default +
 * org-override shape as V47: a tenant's key is visible only in its org's context (or platform), a GLOBAL
 * key (org_id NULL — the platform key and tenant fallback) is visible in every context including the
 * no-context windows (startup key generation / the unauthenticated JWKS endpoint), and WITH CHECK refuses
 * a tenant-bound connection writing a global key or another org's key. Not {@code @Transactional}.
 */
class SigningKeyRlsIT extends AbstractIntegrationTest {

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
    void rlsIsolatesTenantSigningKeysWhileKeepingGlobalKeysVisibleEverywhere() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("key-a");
        UUID orgB = newOrg("key-b");
        String globalKid = "rls-global-" + s;
        String aKid = "rls-a-" + s;
        String bKid = "rls-b-" + s;
        seedKey(globalKid, null);
        seedKey(aKid, orgA);
        seedKey(bKid, orgB);

        createProbeRole();
        try (Connection probe = DriverManager.getConnection(jdbcUrl(), "rls_probe", "probe")) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, globalKid)).isTrue();
            assertThat(visible(probe, aKid)).isTrue();
            assertThat(visible(probe, bKid)).isFalse();

            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, globalKid)).isTrue();
            assertThat(visible(probe, bKid)).isTrue();
            assertThat(visible(probe, aKid)).isFalse();

            // No context (startup key generation / the unauthenticated JWKS endpoint): ONLY global keys.
            resetContext(probe);
            assertThat(visible(probe, globalKid)).isTrue();
            assertThat(visible(probe, aKid)).isFalse();
            assertThat(visible(probe, bKid)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, globalKid)).isTrue();
            assertThat(visible(probe, aKid)).isTrue();
            assertThat(visible(probe, bKid)).isTrue();

            // WITH CHECK on writes (tighter than the read side):
            resetContext(probe);
            insertKey(probe, "rls-w-global-" + s, null);            // a GLOBAL key writable only with no context
            setContext(probe, "app.current_org", orgA.toString());
            insertKey(probe, "rls-w-a-" + s, orgA);                 // A's own key in A's context — allowed
            assertThatThrownBy(() -> insertKey(probe, "rls-w-b-" + s, orgB)) // another org's key — refused
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertKey(probe, "rls-w-global2-" + s, null)) // a global key from a
                    .isInstanceOf(SQLException.class);                   // tenant-bound context — refused
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> organizations.delete(id)); // FK cascade removes the org's keys
        return id;
    }

    private void seedKey(String kid, UUID orgId) {
        jdbc.update("insert into signing_key (id, kid, algorithm, public_key, private_key, org_id) "
                + "values (gen_random_uuid(), ?, 'RS256', 'pub', 'priv', ?)", kid, orgId);
        cleanups.add(() -> jdbc.update("delete from signing_key where kid = ?", kid));
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
        jdbc.execute("GRANT SELECT, INSERT, DELETE ON signing_key TO rls_probe");
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

    private boolean visible(Connection c, String kid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from signing_key where kid = ?")) {
            ps.setString(1, kid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertKey(Connection c, String kid, UUID orgId) throws SQLException {
        cleanups.add(() -> jdbc.update("delete from signing_key where kid = ?", kid));
        try (PreparedStatement ps = c.prepareStatement("insert into signing_key "
                + "(id, kid, algorithm, public_key, private_key, org_id) "
                + "values (gen_random_uuid(), ?, 'RS256', 'pub', 'priv', ?)")) {
            ps.setString(1, kid);
            ps.setObject(2, orgId);
            ps.executeUpdate();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
