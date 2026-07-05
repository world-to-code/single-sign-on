package com.example.sso.portal;

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
 * Proves the org-scoping RLS on {@code app_assignment} against the application's real NON-SUPERUSER runtime
 * role ({@code sso_app}) — a superuser bypasses RLS. Global + org-override form: a global assignment (e.g. the
 * seeded admin-console grant) is visible in every context, a tenant assignment is visible only in its org's
 * context (or platform), and WITH CHECK refuses a tenant-bound connection writing a global assignment or
 * another org's assignment. Seeding/teardown use the privileged owner connection ({@link #ownerJdbc()}); the
 * isolation assertions use a raw {@link #appRoleConnection()}. Not {@code @Transactional}.
 */
class AppAssignmentRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void rlsIsolatesTenantAssignmentsWhileKeepingGlobalOnesVisibleEverywhere() throws SQLException {
        UUID orgA = newOrg("appasg-a");
        UUID orgB = newOrg("appasg-b");
        UUID global = seed(null);
        UUID a = seed(orgA);
        UUID b = seed(orgB);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, global)).isTrue();
            assertThat(visible(probe, a)).isTrue();
            assertThat(visible(probe, b)).isFalse();

            resetContext(probe); // no context: only global assignments resolve (fail-closed default)
            assertThat(visible(probe, global)).isTrue();
            assertThat(visible(probe, a)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, a)).isTrue();
            assertThat(visible(probe, b)).isTrue();

            resetContext(probe);
            insertRow(probe, null);                                 // a global assignment writable with no context
            setContext(probe, "app.current_org", orgA.toString());
            insertRow(probe, orgA);                                 // A's own assignment in A's context — allowed
            assertThatThrownBy(() -> insertRow(probe, orgB)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertRow(probe, null)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void perTierUniquenessAllowsTheSameAppAndSubjectAcrossOrgsButNotWithinOne() throws SQLException {
        UUID orgA = newOrg("appuq-a");
        UUID orgB = newOrg("appuq-b");
        String appId = "shared-app-" + suffix();
        UUID subject = UUID.randomUUID(); // a GLOBAL user identity assigned in two tenants

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            insertKeyed(probe, appId, subject, orgA);                                  // org A grant — allowed
            setContext(probe, "app.current_org", orgB.toString());
            insertKeyed(probe, appId, subject, orgB);                                  // org B grant — independent
            setContext(probe, "app.current_org", orgA.toString());
            assertThatThrownBy(() -> insertKeyed(probe, appId, subject, orgA))         // duplicate within org A
                    .isInstanceOf(SQLException.class);

            resetContext(probe);
            insertKeyed(probe, appId, subject, null);                                  // a global grant — allowed
            assertThatThrownBy(() -> insertKeyed(probe, appId, subject, null))         // duplicate global
                    .isInstanceOf(SQLException.class);
        }
    }

    private void insertKeyed(Connection c, String appId, UUID subjectId, UUID orgId) throws SQLException {
        UUID id = UUID.randomUUID();
        cleanups.add(() -> ownerJdbc().update("delete from app_assignment where id = ?", id));
        try (PreparedStatement ps = c.prepareStatement("insert into app_assignment "
                + "(id, app_type, app_id, subject_type, subject_id, org_id) values (?, 'OIDC', ?, 'USER', ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, appId);
            ps.setObject(3, subjectId);
            ps.setObject(4, orgId);
            ps.executeUpdate();
        }
    }

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        // Delete via the owner: an org's cascade-deletes hit RLS-guarded child rows the app role cannot see.
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private UUID seed(UUID orgId) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into app_assignment (id, app_type, app_id, subject_type, subject_id, org_id) "
                + "values (?, 'OIDC', ?, 'USER', ?, ?)", id, "app-" + suffix(), UUID.randomUUID(), orgId);
        cleanups.add(() -> ownerJdbc().update("delete from app_assignment where id = ?", id));
        return id;
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

    private boolean visible(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from app_assignment where id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertRow(Connection c, UUID orgId) throws SQLException {
        UUID id = UUID.randomUUID();
        cleanups.add(() -> ownerJdbc().update("delete from app_assignment where id = ?", id));
        try (PreparedStatement ps = c.prepareStatement("insert into app_assignment "
                + "(id, app_type, app_id, subject_type, subject_id, org_id) values (?, 'OIDC', ?, 'USER', ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "app-" + suffix());
            ps.setObject(3, UUID.randomUUID());
            ps.setObject(4, orgId);
            ps.executeUpdate();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
