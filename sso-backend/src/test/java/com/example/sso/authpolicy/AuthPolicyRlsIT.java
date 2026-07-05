package com.example.sso.authpolicy;

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
 * Proves the org-scoping RLS on the {@code auth_policy} table against the application's real NON-SUPERUSER
 * runtime role ({@code sso_app}) — a superuser (the Testcontainers/dev default) bypasses RLS. Auth policies
 * are resolved LIVE during the login flow (identify / each factor step), which runs before any org context
 * is bound, so — exactly like {@code role} — the policy keeps an {@code org_id IS NULL} clause so GLOBAL
 * policies (the seeded Default, platform-wide policies) stay visible in EVERY context:
 * <ul>
 *   <li>a tenant policy is visible only in its org's context (or platform), never another org's nor unset;</li>
 *   <li>a GLOBAL policy (org_id NULL) is visible in every context — org A, org B, platform, and unset
 *       (the no-context login window and startup seeding);</li>
 *   <li>WITH CHECK: a global (NULL) policy is writable in any context (seeder), a tenant policy only in its
 *       own org's context; a cross-org write is refused.</li>
 * </ul>
 * Seeding/teardown use the privileged owner connection ({@link #ownerJdbc()}); the isolation assertions use a
 * raw {@link #appRoleConnection()}. Not {@code @Transactional} — each probe call is its own connection.
 */
class AuthPolicyRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void rlsIsolatesTenantPoliciesWhileKeepingGlobalPoliciesVisibleEverywhere() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("policy-a");
        UUID orgB = newOrg("policy-b");
        // Seed one policy per tier via the owner connection (superuser bypasses RLS, so we control org_id).
        String globalName = "rls-global-" + s;
        String aName = "rls-a-" + s;
        String bName = "rls-b-" + s;
        seedPolicy(globalName, null);
        seedPolicy(aName, orgA);
        seedPolicy(bName, orgB);

        try (Connection probe = appRoleConnection()) {
            // Org A's context: sees the global policy + A's policy, never B's.
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, aName)).isTrue();
            assertThat(visible(probe, bName)).isFalse();

            // Org B's context: global + B's, never A's.
            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, bName)).isTrue();
            assertThat(visible(probe, aName)).isFalse();

            // No context (the pre-org login window / seeder): ONLY global policies — tenant ones fail closed.
            resetContext(probe);
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, aName)).isFalse();
            assertThat(visible(probe, bName)).isFalse();

            // Platform: everything.
            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, aName)).isTrue();
            assertThat(visible(probe, bName)).isTrue();

            // WITH CHECK on writes:
            resetContext(probe);
            insertPolicy(probe, "rls-w-global-" + s, null);           // a GLOBAL policy writable with no context (seeder)
            setContext(probe, "app.current_org", orgA.toString());
            insertPolicy(probe, "rls-w-a-" + s, orgA);                // A's own policy in A's context — allowed
            assertThatThrownBy(() -> insertPolicy(probe, "rls-w-b-" + s, orgB)) // another org's policy — refused
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

    /** Insert a policy directly (owner connection bypasses RLS) so we control its tier. */
    private void seedPolicy(String name, UUID orgId) {
        ownerJdbc().update("insert into auth_policy (id, name, org_id) values (gen_random_uuid(), ?, ?)", name, orgId);
        cleanups.add(() -> ownerJdbc().update("delete from auth_policy where name = ?", name));
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
        try (PreparedStatement ps = c.prepareStatement("select count(*) from auth_policy where name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertPolicy(Connection c, String name, UUID orgId) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from auth_policy where name = ?", name));
        try (PreparedStatement ps = c.prepareStatement(
                "insert into auth_policy (id, name, org_id) values (gen_random_uuid(), ?, ?)")) {
            ps.setString(1, name);
            ps.setObject(2, orgId);
            ps.executeUpdate();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
