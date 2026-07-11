package com.example.sso.organization;

import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the two halves of the RLS isolation on {@code organization_membership}:
 * <ol>
 *   <li>{@code OrgAwareDataSource} sets the {@code app.current_org}/{@code app.platform} GUCs from the
 *       current {@link OrgContext} on the connection (checked via {@code current_setting}); and</li>
 *   <li>the RLS policy isolates rows by org, allows the platform bypass, fails closed with no context, and
 *       enforces WITH CHECK on writes — verified against the application's real NON-SUPERUSER runtime role
 *       ({@code sso_app}), because a superuser (the Testcontainers/dev default) bypasses RLS entirely. In
 *       production the app's runtime role MUST likewise be a non-superuser for RLS to take effect.</li>
 * </ol>
 * Not {@code @Transactional} — each call is its own tx/connection.
 */
class OrganizationRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;
    @Autowired
    UserService users;
    @Autowired
    OrgContext orgContext;
    @Autowired
    JdbcTemplate jdbc;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void orgAwareDataSourceAppliesTheTenantGucFromContext() {
        UUID orgA = newOrg("guc-a");

        assertThat(orgContext.callInOrg(orgA, () -> currentSetting("app.current_org"))).isEqualTo(orgA.toString());
        assertThat(orgContext.callInOrg(orgA, () -> currentSetting("app.platform"))).isBlank();
        assertThat(orgContext.callAsPlatform(() -> currentSetting("app.platform"))).isEqualTo("on");
        assertThat(orgContext.callAsPlatform(() -> currentSetting("app.current_org"))).isBlank();
        assertThat(currentSetting("app.current_org")).isBlank(); // no context bound
    }

    @Test
    void rlsIsolatesMembershipByOrgForANonSuperuserRole() throws SQLException {
        UUID orgA = newOrg("rls-a");
        UUID orgB = newOrg("rls-b");
        String s = suffix();
        UUID user = users.createUser(new NewUser("rls-u-" + s, "rls-u-" + s + "@example.com",
                "Rls", "rls-pass-1!", Set.of("ROLE_USER"))).getId();
        cleanups.add(() -> users.delete(user));
        organizations.addMember(orgA, user); // user belongs to A only

        try (Connection probe = appRoleConnection()) {
            // USING: visible only in A's context / platform; never in B or with no context (fail-closed).
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(countFor(probe, user)).isEqualTo(1);
            setContext(probe, "app.current_org", orgB.toString());
            assertThat(countFor(probe, user)).isZero();
            setContext(probe, "app.platform", "on");
            assertThat(countFor(probe, user)).isEqualTo(1);
            resetContext(probe);
            assertThat(countFor(probe, user)).isZero();

            // WITH CHECK: cannot insert a row for org B while bound to org A; platform may.
            setContext(probe, "app.current_org", orgB.toString()); // bound to B
            assertThatThrownBy(() -> insertFor(probe, orgA, user)).isInstanceOf(SQLException.class);
            setContext(probe, "app.platform", "on");
            insertFor(probe, orgB, user); // platform write for any org is allowed
            assertThat(countFor(probe, user)).isEqualTo(2);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        // Delete via the owner: an org's cascade-deletes hit RLS-guarded child rows the app role cannot see.
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private String currentSetting(String key) {
        return jdbc.queryForObject("select current_setting(?, true)", String.class, key);
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

    private long countFor(Connection c, UUID user) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select count(*) from organization_membership where user_id = ?")) {
            ps.setObject(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertFor(Connection c, UUID orgId, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into organization_membership (id, org_id, user_id) values (gen_random_uuid(), ?, ?)")) {
            ps.setObject(1, orgId);
            ps.setObject(2, userId);
            ps.executeUpdate();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
