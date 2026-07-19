package com.example.sso.directory;

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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Org scoping on the three directory-sync tables, against the application's real NON-SUPERUSER role — a
 * superuser bypasses RLS, so an isolation bug would go unseen in a service test whose queries already carry an
 * org predicate.
 *
 * <p>What a leak would mean here is unusually concrete: a connector row holds the host, the bind DN and the
 * encrypted bind password for someone else's corporate directory. Reading another tenant's row exposes their
 * internal topology; writing one would repoint their sync at a directory the attacker controls.
 */
class DirectoryConnectorRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void aTenantSeesOnlyItsOwnConnectorsAndNeverThePlatformTiers() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("dir-a");
        UUID orgB = newOrg("dir-b");
        seedConnector("global-" + s, null);
        seedConnector("a-" + s, orgA);
        seedConnector("b-" + s, orgB);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, "a-" + s)).isTrue();
            assertThat(visible(probe, "b-" + s)).isFalse();
            assertThat(visible(probe, "global-" + s)).isFalse(); // a connector is never inherited

            resetContext(probe);
            assertThat(visible(probe, "a-" + s)).isFalse(); // no context, no rows

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, "global-" + s)).isTrue();
        }
    }

    @Test
    void aTenantMayRegisterOnlyItsOwnConnector() throws SQLException {
        String s = suffix();
        UUID orgC = newOrg("dir-c");
        UUID orgD = newOrg("dir-d");

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgC.toString());
            insertConnector(probe, "own-" + s, orgC);
            assertRlsRefuses(() -> insertConnector(probe, "foreign-" + s, orgD));
            assertRlsRefuses(() -> insertConnector(probe, "forged-" + s, null));
            // Re-tiering would hand another tenant a connection — and its bind credential.
            assertRlsRefuses(() -> retier(probe, "own-" + s, orgD));
        }
    }

    /** The run history says who synced, when, and how much matched — also a tenant's own business. */
    @Test
    void syncRunsAndMappingsAreScopedToo() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("dir-runs-a");
        UUID orgB = newOrg("dir-runs-b");
        UUID connectorA = seedConnector("ra-" + s, orgA);
        UUID connectorB = seedConnector("rb-" + s, orgB);
        seedRun(connectorA, orgA);
        seedRun(connectorB, orgB);
        seedMapping(connectorA, orgA, "department");
        seedMapping(connectorB, orgB, "department");

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(countFor(probe, "directory_sync_run", connectorA)).isOne();
            assertThat(countFor(probe, "directory_sync_run", connectorB)).isZero();
            assertThat(countFor(probe, "directory_attribute_mapping", connectorA)).isOne();
            assertThat(countFor(probe, "directory_attribute_mapping", connectorB)).isZero();
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private UUID seedConnector(String name, UUID orgId) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("""
                insert into directory_connector
                    (id, org_id, name, display_name, kind, host, port, use_ssl, base_dn)
                values (?, ?, ?, 'Probe', 'LDAP', 'ldap.probe.test', 636, true, 'dc=probe')""",
                id, orgId, name);
        cleanups.add(() -> ownerJdbc().update("delete from directory_connector where id = ?", id));
        return id;
    }

    private void seedRun(UUID connectorId, UUID orgId) {
        ownerJdbc().update("""
                insert into directory_sync_run (id, connector_id, org_id, status)
                values (gen_random_uuid(), ?, ?, 'SUCCEEDED')""", connectorId, orgId);
    }

    private void seedMapping(UUID connectorId, UUID orgId, String source) {
        ownerJdbc().update("""
                insert into directory_attribute_mapping
                    (id, connector_id, org_id, source_attribute, target_key)
                values (gen_random_uuid(), ?, ?, ?, ?)""", connectorId, orgId, source, source);
    }

    private void insertConnector(Connection c, String name, UUID orgId) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from directory_connector where name = ?", name));
        try (PreparedStatement ps = c.prepareStatement("""
                insert into directory_connector
                    (id, org_id, name, display_name, kind, host, port, use_ssl, base_dn)
                values (gen_random_uuid(), ?, ?, 'Probe', 'LDAP', 'ldap.probe.test', 636, true, 'dc=probe')""")) {
            ps.setObject(1, orgId);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private void retier(Connection c, String name, UUID newOrgId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "update directory_connector set org_id = ? where name = ?")) {
            ps.setObject(1, newOrgId);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private long countFor(Connection c, String table, UUID connectorId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select count(*) from " + table + " where connector_id = ?")) {
            ps.setObject(1, connectorId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void setContext(Connection c, String setting, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config(?, ?, false)")) {
            ps.setString(1, setting);
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
        try (PreparedStatement ps = c.prepareStatement(
                "select count(*) from directory_connector where name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void assertRlsRefuses(ThrowingCallable write) {
        assertThatThrownBy(write)
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
