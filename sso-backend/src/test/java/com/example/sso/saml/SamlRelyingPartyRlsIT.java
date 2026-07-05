package com.example.sso.saml;

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
 * Proves the org-scoping RLS on {@code saml_relying_party} through a dedicated <b>non-superuser</b> role.
 * The entityId is globally unique, so RLS — not a per-tier name split — is what isolates a tenant RP:
 * a global RP is visible in every context (SSO resolves by entityId before the request is bound), a
 * tenant RP is visible only in its org's context (or platform), and WITH CHECK refuses a tenant-bound
 * connection writing a global RP or another org's RP. Not {@code @Transactional}.
 */
class SamlRelyingPartyRlsIT extends AbstractIntegrationTest {

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
    void rlsIsolatesTenantRelyingPartiesWhileKeepingGlobalOnesVisibleEverywhere() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("saml-a");
        UUID orgB = newOrg("saml-b");
        String global = "urn:rp:global:" + s;
        String a = "urn:rp:a:" + s;
        String b = "urn:rp:b:" + s;
        seed(global, null);
        seed(a, orgA);
        seed(b, orgB);

        createProbeRole();
        try (Connection probe = DriverManager.getConnection(jdbcUrl(), "rls_probe", "probe")) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, global)).isTrue();
            assertThat(visible(probe, a)).isTrue();
            assertThat(visible(probe, b)).isFalse();

            // No context is the fail-closed default: a global RP stays visible, an org RP does not. The
            // browser-less SLO paths (propagation/chain) run here UNBOUND, so they must resolve each RP in
            // platform context (callAsPlatform) — otherwise an org RP is invisible and its logout is skipped.
            resetContext(probe);
            assertThat(visible(probe, global)).isTrue();
            assertThat(visible(probe, a)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, a)).isTrue();
            assertThat(visible(probe, b)).isTrue();

            resetContext(probe);
            insertRow(probe, "urn:rp:w-global:" + s, null);       // a global RP writable with no context
            setContext(probe, "app.current_org", orgA.toString());
            insertRow(probe, "urn:rp:w-a:" + s, orgA);            // A's own RP in A's context — allowed
            assertThatThrownBy(() -> insertRow(probe, "urn:rp:w-b:" + s, orgB)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertRow(probe, "urn:rp:w-g2:" + s, null)).isInstanceOf(SQLException.class);
        }
    }

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> organizations.delete(id));
        return id;
    }

    private void seed(String entityId, UUID orgId) {
        jdbc.update("insert into saml_relying_party (id, entity_id, acs_url, org_id) "
                + "values (gen_random_uuid(), ?, ?, ?)", entityId, "https://sp.example/acs", orgId);
        cleanups.add(() -> jdbc.update("delete from saml_relying_party where entity_id = ?", entityId));
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
        jdbc.execute("GRANT SELECT, INSERT, DELETE ON saml_relying_party TO rls_probe");
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

    private boolean visible(Connection c, String entityId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select count(*) from saml_relying_party where entity_id = ?")) {
            ps.setString(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertRow(Connection c, String entityId, UUID orgId) throws SQLException {
        cleanups.add(() -> jdbc.update("delete from saml_relying_party where entity_id = ?", entityId));
        try (PreparedStatement ps = c.prepareStatement("insert into saml_relying_party "
                + "(id, entity_id, acs_url, org_id) values (gen_random_uuid(), ?, ?, ?)")) {
            ps.setString(1, entityId);
            ps.setString(2, "https://sp.example/acs");
            ps.setObject(3, orgId);
            ps.executeUpdate();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
