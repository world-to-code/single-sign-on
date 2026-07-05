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
 * Proves the org-scoping RLS on {@code saml_credential} (which holds tenant SAML private keys, encrypted at
 * rest), verified through a dedicated <b>non-superuser</b> role. Unlike the other tables this is the STRICT
 * membership form (org_id is NOT NULL — the global SAML credential is the file keystore, not a DB row): a
 * tenant's credential is visible/writable only in its own org's context (or platform), never another org's
 * nor an unset context. Not {@code @Transactional}.
 */
class SamlCredentialRlsIT extends AbstractIntegrationTest {

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
    void rlsIsolatesTenantSamlCredentials() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("saml-a");
        UUID orgB = newOrg("saml-b");
        String aCert = "cert-a-" + s;
        String bCert = "cert-b-" + s;
        seed(orgA, aCert);
        seed(orgB, bCert);

        createProbeRole();
        try (Connection probe = DriverManager.getConnection(jdbcUrl(), "rls_probe", "probe")) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, aCert)).isTrue();
            assertThat(visible(probe, bCert)).isFalse();

            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, bCert)).isTrue();
            assertThat(visible(probe, aCert)).isFalse();

            // No context: strict membership form has no global rows — nothing visible.
            resetContext(probe);
            assertThat(visible(probe, aCert)).isFalse();
            assertThat(visible(probe, bCert)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, aCert)).isTrue();
            assertThat(visible(probe, bCert)).isTrue();

            // WITH CHECK: A's own row in A's context is allowed; another org's row is refused. Clear the
            // platform bypass first (it was set above) so the org check actually applies.
            resetContext(probe);
            setContext(probe, "app.current_org", orgA.toString());
            insertRow(probe, orgA, "cert-w-a-" + s);
            assertThatThrownBy(() -> insertRow(probe, orgB, "cert-w-b-" + s))
                    .isInstanceOf(SQLException.class);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> organizations.delete(id));
        return id;
    }

    private void seed(UUID orgId, String cert) {
        jdbc.update("insert into saml_credential (id, org_id, certificate, private_key) "
                + "values (gen_random_uuid(), ?, ?, 'key')", orgId, cert);
        cleanups.add(() -> jdbc.update("delete from saml_credential where certificate = ?", cert));
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
        jdbc.execute("GRANT SELECT, INSERT, DELETE ON saml_credential TO rls_probe");
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

    private boolean visible(Connection c, String cert) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from saml_credential where certificate = ?")) {
            ps.setString(1, cert);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertRow(Connection c, UUID orgId, String cert) throws SQLException {
        cleanups.add(() -> jdbc.update("delete from saml_credential where certificate = ?", cert));
        try (PreparedStatement ps = c.prepareStatement("insert into saml_credential "
                + "(id, org_id, certificate, private_key) values (gen_random_uuid(), ?, ?, 'key')")) {
            ps.setObject(1, orgId);
            ps.setString(2, cert);
            ps.executeUpdate();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
