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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the org-scoping RLS on {@code user_group} against the application's real NON-SUPERUSER runtime role
 * ({@code sso_app}) — a superuser bypasses RLS. Like {@code role}, the policy keeps GLOBAL groups (org_id NULL
 * — e.g. "All Users") visible in every context, which login group-delegated-role resolution and the seeder
 * depend on: a tenant group is visible only in its org's context (or platform); a global group is visible in
 * org A, org B, platform, and unset; a global (NULL) group is writable in any context, a tenant group only in
 * its own org's context. Seeding/teardown use the privileged owner connection ({@link #ownerJdbc()}); the
 * isolation assertions use a raw {@link #appRoleConnection()}.
 */
class GroupRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void rlsIsolatesTenantGroupsWhileKeepingGlobalGroupsVisibleEverywhere() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("grp-a");
        UUID orgB = newOrg("grp-b");
        String globalName = "rls-g-global-" + s;
        String aName = "rls-g-a-" + s;
        String bName = "rls-g-b-" + s;
        seedGroup(globalName, null);
        seedGroup(aName, orgA);
        seedGroup(bName, orgB);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, aName)).isTrue();
            assertThat(visible(probe, bName)).isFalse();

            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, bName)).isTrue();
            assertThat(visible(probe, aName)).isFalse();

            resetContext(probe); // login group resolution / seeder: only global groups visible
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, aName)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, aName)).isTrue();
            assertThat(visible(probe, bName)).isTrue();

            // WITH CHECK on writes:
            resetContext(probe);
            insertGroup(probe, "rls-gw-global-" + s, null);          // global group writable with no context (seeder)
            setContext(probe, "app.current_org", orgA.toString());
            insertGroup(probe, "rls-gw-a-" + s, orgA);               // own-org group — allowed
            assertThatThrownBy(() -> insertGroup(probe, "rls-gw-b-" + s, orgB)) // another org's group — refused
                    .isInstanceOf(SQLException.class);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        // Delete via the owner: an org's cascade-deletes hit RLS-guarded child rows the app role cannot see.
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private void seedGroup(String name, UUID orgId) {
        ownerJdbc().update("insert into user_group (id, name, org_id, system, created_at) "
                + "values (gen_random_uuid(), ?, ?, false, now())", name, orgId);
        cleanups.add(() -> ownerJdbc().update("delete from user_group where name = ?", name));
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
        try (PreparedStatement ps = c.prepareStatement("select count(*) from user_group where name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertGroup(Connection c, String name, UUID orgId) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from user_group where name = ?", name));
        try (PreparedStatement ps = c.prepareStatement("insert into user_group (id, name, org_id, system, created_at) "
                + "values (gen_random_uuid(), ?, ?, false, now())")) {
            ps.setString(1, name);
            ps.setObject(2, orgId);
            ps.executeUpdate();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
