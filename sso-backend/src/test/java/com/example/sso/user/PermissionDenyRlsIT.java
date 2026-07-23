package com.example.sso.user;

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
 * Proves the SPLIT RLS on {@code org_permission_deny} against the real non-superuser runtime role. A negative
 * permission is authored per tier, and the platform veto ({@code org_id} NULL) is the strongest authority in the
 * system — so the storage layer must let a tenant SEE it (for provenance) but never WRITE or DELETE it. The
 * naive single-policy recipe (which opens {@code org_id IS NULL} to every tenant, and {@code USING} governs
 * DELETE) would have let a tenant delete the platform veto; these probes are that regression, at the DB.
 */
class PermissionDenyRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // LIFO: a dependent row (e.g. a user in an org) is torn down before the thing it references (the org).
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void aTenantSeesButCanNeverWriteOrDeleteThePlatformVeto() throws SQLException {
        String pattern = "user:read#" + suffix();
        seedDeny(null, pattern); // the platform veto

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", newOrg("deny-a").toString());

            assertThat(count(probe, pattern)).isEqualTo(1); // SELECT: visible for provenance

            int deleted = delete(probe, pattern);
            assertThat(deleted).isZero();                   // DELETE: RLS hides the platform row from a tenant
            assertThat(count(probe, pattern)).isEqualTo(1); // ... so the veto still stands

            assertRlsRefuses(() -> insert(probe, null, "forge:veto#" + suffix())); // INSERT org_id NULL: refused
        }

        try (Connection platform = appRoleConnection()) {
            setContext(platform, "app.platform", "on");
            assertThat(delete(platform, pattern)).isEqualTo(1); // only the platform context may retire it
        }
    }

    @Test
    void rlsIsolatesTenantDeniesAndLetsATenantWriteOnlyItsOwn() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("deny-iso-a");
        UUID orgB = newOrg("deny-iso-b");
        String aPattern = "role:delete#a" + s;
        String bPattern = "role:delete#b" + s;
        seedDeny(orgA, aPattern);
        seedDeny(orgB, bPattern);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(count(probe, aPattern)).isEqualTo(1); // own
            assertThat(count(probe, bPattern)).isZero();      // never the other tenant's

            insert(probe, orgA, "group:update#own" + s);                       // own org — allowed
            assertRlsRefuses(() -> insert(probe, orgB, "group:update#foreign" + s)); // another org — refused
        }
    }

    @Test
    void aTenantCannotRelabelItsOwnOrgDenyAsThePlatformVeto() throws SQLException {
        UUID orgA = newOrg("deny-relabel");
        String pattern = "user:update#" + suffix();
        seedDeny(orgA, pattern);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            // Own row is writable, but promoting it to a platform veto (org_id -> NULL) fails the WITH CHECK.
            assertRlsRefuses(() -> {
                try (PreparedStatement ps = probe.prepareStatement(
                        "update org_permission_deny set org_id = null where pattern = ?")) {
                    ps.setString(1, pattern);
                    ps.executeUpdate();
                }
            });
        }
    }

    @Test
    void aTenantSeesButCanNeverWriteOrDeleteAPlatformPrincipalVeto() throws SQLException {
        // The split RLS is hand-written per table; this is the copy-paste regression probe for
        // principal_permission_deny (contrast org_permission_deny above).
        UUID roleId = UUID.randomUUID();
        String pattern = "role:delete#" + suffix();
        ownerJdbc().update("insert into principal_permission_deny (id, subject_type, subject_id, org_id, pattern) "
                + "values (gen_random_uuid(), 'ROLE', ?, null, ?)", roleId, pattern);
        cleanups.add(() -> ownerJdbc().update("delete from principal_permission_deny where pattern = ?", pattern));

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", newOrg("deny-pp").toString());
            assertThat(countIn(probe, "principal_permission_deny", pattern)).isEqualTo(1); // visible
            assertThat(deleteIn(probe, "principal_permission_deny", pattern)).isZero();     // cannot delete
            assertThat(countIn(probe, "principal_permission_deny", pattern)).isEqualTo(1);  // ... veto survives
            assertRlsRefuses(() -> insertPrincipal(probe, UUID.randomUUID(), null, "forge#" + suffix()));
        }
    }

    @Test
    void aTenantCannotAuthorAUserDenyNamingAUserInAnotherOrg() throws SQLException {
        UUID orgA = newOrg("deny-fk-a");
        UUID orgB = newOrg("deny-fk-b");
        UUID userInA = seedUser(orgA);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgB.toString());
            // RLS WITH CHECK (org_id = current_org = B) would accept it, but the composite FK
            // (user_id, org_id) -> app_user(id, org_id) rejects (userInA, B) — no such pair exists.
            assertThatThrownBy(() -> insertUserDeny(probe, userInA, orgB, "user:read#" + suffix()))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23503"));
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID seedUser(UUID orgId) {
        UUID id = UUID.randomUUID();
        String name = "deny-u-" + suffix();
        ownerJdbc().update("insert into app_user (id, username, email, org_id) values (?, ?, ?, ?)",
                id, name, name + "@example.com", orgId);
        cleanups.add(() -> ownerJdbc().update("delete from app_user where id = ?", id));
        return id;
    }

    private long countIn(Connection c, String table, String pattern) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from " + table + " where pattern = ?")) {
            ps.setString(1, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private int deleteIn(Connection c, String table, String pattern) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("delete from " + table + " where pattern = ?")) {
            ps.setString(1, pattern);
            return ps.executeUpdate();
        }
    }

    private void insertPrincipal(Connection c, UUID subjectId, UUID orgId, String pattern) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from principal_permission_deny where pattern = ?", pattern));
        try (PreparedStatement ps = c.prepareStatement("insert into principal_permission_deny "
                + "(id, subject_type, subject_id, org_id, pattern) values (gen_random_uuid(), 'ROLE', ?, ?, ?)")) {
            ps.setObject(1, subjectId);
            ps.setObject(2, orgId);
            ps.setString(3, pattern);
            ps.executeUpdate();
        }
    }

    private void insertUserDeny(Connection c, UUID userId, UUID orgId, String pattern) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from app_user_permission_deny where pattern = ?", pattern));
        try (PreparedStatement ps = c.prepareStatement("insert into app_user_permission_deny "
                + "(id, user_id, org_id, pattern) values (gen_random_uuid(), ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setObject(2, orgId);
            ps.setString(3, pattern);
            ps.executeUpdate();
        }
    }

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private void seedDeny(UUID orgId, String pattern) {
        ownerJdbc().update(
                "insert into org_permission_deny (id, org_id, pattern) values (gen_random_uuid(), ?, ?)",
                orgId, pattern);
        cleanups.add(() -> ownerJdbc().update("delete from org_permission_deny where pattern = ?", pattern));
    }

    private void setContext(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config(?, ?, false)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.execute();
        }
    }

    private long count(Connection c, String pattern) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from org_permission_deny where pattern = ?")) {
            ps.setString(1, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private int delete(Connection c, String pattern) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("delete from org_permission_deny where pattern = ?")) {
            ps.setString(1, pattern);
            return ps.executeUpdate();
        }
    }

    private void insert(Connection c, UUID orgId, String pattern) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from org_permission_deny where pattern = ?", pattern));
        try (PreparedStatement ps = c.prepareStatement(
                "insert into org_permission_deny (id, org_id, pattern) values (gen_random_uuid(), ?, ?)")) {
            ps.setObject(1, orgId);
            ps.setString(2, pattern);
            ps.executeUpdate();
        }
    }

    /** Asserts the write was refused by the RLS policy specifically (SQLState 42501), not an incidental error. */
    private void assertRlsRefuses(ThrowingCallable write) {
        assertThatThrownBy(write)
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
