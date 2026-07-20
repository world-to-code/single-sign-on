package com.example.sso.metadata;

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
 * Org scoping on {@code profile}, against the application's real NON-SUPERUSER role. A superuser bypasses RLS
 * entirely, so an isolation bug is invisible to a service test whose queries already carry an org predicate —
 * the predicate would be doing all the work and nobody would notice the policy was missing.
 *
 * <p>A profile is about to become the thing that decides what a user's attributes ARE, and those attributes
 * drive ABAC role grants. Reading another tenant's profile would disclose their org structure; writing one
 * would let an attacker define the schema their rules match on.
 */
class ProfileRlsIT extends AbstractIntegrationTest {

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
    void aTenantSeesOnlyItsOwnProfiles() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("prof-a");
        UUID orgB = newOrg("prof-b");
        seedProfile("a-" + s, orgA);
        seedProfile("b-" + s, orgB);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, "a-" + s)).isTrue();
            assertThat(visible(probe, "b-" + s)).isFalse();

            resetContext(probe);
            assertThat(visible(probe, "a-" + s)).isFalse(); // no context, no rows — fail closed

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, "b-" + s)).isTrue(); // the platform tier sees across, by design
        }
    }

    @Test
    void aTenantMayCreateOnlyItsOwnProfile() throws SQLException {
        String s = suffix();
        UUID orgC = newOrg("prof-c");
        UUID orgD = newOrg("prof-d");

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgC.toString());
            insertProfile(probe, "own-" + s, orgC);
            assertRlsRefuses(() -> insertProfile(probe, "foreign-" + s, orgD));
            // Re-tiering would hand another tenant a schema their own rules then match against.
            assertRlsRefuses(() -> retier(probe, "own-" + s, orgD));
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private void seedProfile(String name, UUID orgId) {
        ownerJdbc().update("insert into profile (id, org_id, name, kind) values (?, ?, ?, 'TENANT')",
                UUID.randomUUID(), orgId, name);
    }

    private void insertProfile(Connection c, String name, UUID orgId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into profile (id, org_id, name, kind) values (?, ?, ?, 'TENANT')")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, orgId);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private void retier(Connection c, String name, UUID toOrg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("update profile set org_id = ? where name = ?")) {
            ps.setObject(1, toOrg);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private boolean visible(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select 1 from profile where name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void assertRlsRefuses(ThrowingCallable write) {
        assertThatThrownBy(write).isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
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
}
