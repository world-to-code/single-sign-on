package com.example.sso.scim;

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
 * Proves the org-scoping RLS on {@code scim_token} through a dedicated <b>non-superuser</b> role. Global +
 * org-override form (a global token is visible in every context — the token lookup runs cross-org before the
 * request is bound): a tenant token is visible only in its org's context (or platform), and WITH CHECK
 * refuses a tenant-bound connection writing a global token or another org's token. Not {@code @Transactional}.
 */
class ScimTokenRlsIT extends AbstractIntegrationTest {

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
    void rlsIsolatesTenantScimTokensWhileKeepingGlobalTokensVisibleEverywhere() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("scim-a");
        UUID orgB = newOrg("scim-b");
        String global = "h-global-" + s;
        String a = "h-a-" + s;
        String b = "h-b-" + s;
        seed(global, null);
        seed(a, orgA);
        seed(b, orgB);

        createProbeRole();
        try (Connection probe = DriverManager.getConnection(jdbcUrl(), "rls_probe", "probe")) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, global)).isTrue();
            assertThat(visible(probe, a)).isTrue();
            assertThat(visible(probe, b)).isFalse();

            resetContext(probe); // no context: the token lookup runs here (pre-bind) and must see global tokens
            assertThat(visible(probe, global)).isTrue();
            assertThat(visible(probe, a)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, a)).isTrue();
            assertThat(visible(probe, b)).isTrue();

            resetContext(probe);
            insertRow(probe, "h-w-global-" + s, null);        // a global token writable with no context
            setContext(probe, "app.current_org", orgA.toString());
            insertRow(probe, "h-w-a-" + s, orgA);             // A's own token in A's context — allowed
            assertThatThrownBy(() -> insertRow(probe, "h-w-b-" + s, orgB)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertRow(probe, "h-w-g2-" + s, null)).isInstanceOf(SQLException.class);
        }
    }

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> organizations.delete(id));
        return id;
    }

    private void seed(String hash, UUID orgId) {
        jdbc.update("insert into scim_token (id, token_hash, org_id) values (gen_random_uuid(), ?, ?)", hash, orgId);
        cleanups.add(() -> jdbc.update("delete from scim_token where token_hash = ?", hash));
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
        jdbc.execute("GRANT SELECT, INSERT, DELETE ON scim_token TO rls_probe");
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

    private boolean visible(Connection c, String hash) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from scim_token where token_hash = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertRow(Connection c, String hash, UUID orgId) throws SQLException {
        cleanups.add(() -> jdbc.update("delete from scim_token where token_hash = ?", hash));
        try (PreparedStatement ps = c.prepareStatement(
                "insert into scim_token (id, token_hash, org_id) values (gen_random_uuid(), ?, ?)")) {
            ps.setString(1, hash);
            ps.setObject(2, orgId);
            ps.executeUpdate();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
