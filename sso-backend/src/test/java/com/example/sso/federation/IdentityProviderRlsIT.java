package com.example.sso.federation;

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
 * Proves the org-scoping RLS on {@code identity_provider} against the application's real NON-SUPERUSER runtime
 * role ({@code sso_app}) — a superuser (the Testcontainers/dev default) bypasses RLS, so an isolation bug would
 * go unseen in an ordinary service test (whose queries are already {@code WHERE org_id = ?}-predicated). A
 * federation provider row carries a tenant's OAuth client secret; a leak lets another tenant impersonate the
 * victim's upstream. Unlike {@code smtp_settings}, the policy here is STRICT per-tier: a tenant does not even
 * READ the platform-global rows (federation is not inherited), so this asserts, at the storage layer:
 * <ul>
 *   <li>a tenant's row is visible ONLY in its own org's context, never another tenant's, never the global tier;</li>
 *   <li>the platform-global row is visible ONLY in the platform context — never inside a tenant context;</li>
 *   <li>WITH CHECK: a tenant may write only its OWN row; a foreign org_id or a forged global row is refused.</li>
 * </ul>
 * Seeding/teardown use the privileged owner connection ({@link #ownerJdbc()}); the isolation assertions use a
 * raw {@link #appRoleConnection()}. Not {@code @Transactional} — each probe is its own connection.
 */
class IdentityProviderRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void rlsIsolatesTenantProvidersStrictlyAndHidesTheGlobalTierFromTenants() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("idp-a");
        UUID orgB = newOrg("idp-b");
        String globalKey = "global-" + s;
        String aKey = "a-" + s;
        String bKey = "b-" + s;
        seedRow(globalKey, null);
        seedRow(aKey, orgA);
        seedRow(bKey, orgB);

        try (Connection probe = appRoleConnection()) {
            // Org A's context: ONLY its own row — never org B's, and never the global tier (no inheritance).
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, aKey)).isTrue();
            assertThat(visible(probe, bKey)).isFalse();
            assertThat(visible(probe, globalKey)).isFalse();

            // Org B's context: ONLY its own row.
            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, bKey)).isTrue();
            assertThat(visible(probe, aKey)).isFalse();
            assertThat(visible(probe, globalKey)).isFalse();

            // No context (pre-org window / seeder): nothing at all — every row fails closed, global included.
            resetContext(probe);
            assertThat(visible(probe, globalKey)).isFalse();
            assertThat(visible(probe, aKey)).isFalse();
            assertThat(visible(probe, bKey)).isFalse();

            // Platform: everything, including the global tier's own providers.
            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, aKey)).isTrue();
            assertThat(visible(probe, bKey)).isTrue();
            assertThat(visible(probe, globalKey)).isTrue();
        }
    }

    @Test
    void withCheckLetsATenantWriteOnlyItsOwnRowNeverAForeignOrGlobalOne() throws SQLException {
        String s = suffix();
        // Fresh orgs with NO pre-seeded row — a refused write fails PURELY on the RLS WITH CHECK, never masked
        // by the per-org/global partial-unique index (each attempt uses a distinct alias/key).
        UUID orgC = newOrg("idp-c");
        UUID orgD = newOrg("idp-d");

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgC.toString());
            String ownKey = "w-c-" + s;
            insertRow(probe, ownKey, orgC);                                          // own row — allowed
            assertRlsRefuses(() -> insertRow(probe, "w-d-" + s, orgD));              // another org — refused
            assertRlsRefuses(() -> insertRow(probe, "w-g-" + s, null));             // forge global — refused
            assertRlsRefuses(() -> relabel(probe, ownKey, orgD)); // move own row cross-tenant via UPDATE — refused
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    /** Insert a row directly (owner connection bypasses RLS) so we control its tier. {@code key} is alias+client_id. */
    private void seedRow(String key, UUID orgId) {
        ownerJdbc().update("""
                insert into identity_provider
                    (id, org_id, alias, display_name, issuer_uri, client_id, client_secret_encrypted, scopes)
                values (gen_random_uuid(), ?, ?, 'Probe', 'https://idp.example', ?, 'encg:x', 'openid')""",
                orgId, key, key);
        cleanups.add(() -> ownerJdbc().update("delete from identity_provider where client_id = ?", key));
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

    private boolean visible(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from identity_provider where client_id = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertRow(Connection c, String key, UUID orgId) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from identity_provider where client_id = ?", key));
        try (PreparedStatement ps = c.prepareStatement("""
                insert into identity_provider
                    (id, org_id, alias, display_name, issuer_uri, client_id, client_secret_encrypted, scopes)
                values (gen_random_uuid(), ?, ?, 'Probe', 'https://idp.example', ?, 'encg:x', 'openid')""")) {
            ps.setObject(1, orgId);
            ps.setString(2, key);
            ps.setString(3, key);
            ps.executeUpdate();
        }
    }

    private void relabel(Connection c, String key, UUID newOrgId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("update identity_provider set org_id = ? where client_id = ?")) {
            ps.setObject(1, newOrgId);
            ps.setString(2, key);
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
