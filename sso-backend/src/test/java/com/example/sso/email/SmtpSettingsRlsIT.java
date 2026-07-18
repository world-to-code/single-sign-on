package com.example.sso.email;

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
 * Proves the org-scoping RLS on {@code smtp_settings} against the application's real NON-SUPERUSER runtime role
 * ({@code sso_app}) — a superuser (the Testcontainers/dev default) bypasses RLS, so an isolation bug would go
 * unseen in an ordinary service test. A tenant's SMTP credential is one of the most sensitive rows in the
 * system (a leaked relay lets another tenant send mail AS the victim, or read the victim's mail host); this
 * asserts, at the storage layer:
 * <ul>
 *   <li>a tenant's row is visible ONLY in its own org's context (or platform), never another tenant's nor unset;</li>
 *   <li>the platform-wide row (org_id NULL) is visible in every context — so own-else-global resolution works
 *       inside a tenant context without the tenant being able to write it;</li>
 *   <li>WITH CHECK: a tenant may insert/relabel only its OWN row; another org's org_id is refused; the global
 *       (NULL) row is writable only with no bound context (the platform path) — a tenant cannot rewrite it.</li>
 * </ul>
 * Seeding/teardown use the privileged owner connection ({@link #ownerJdbc()}); the isolation assertions use a
 * raw {@link #appRoleConnection()}. Not {@code @Transactional} — each probe is its own connection.
 */
class SmtpSettingsRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void rlsIsolatesTenantSmtpWhileKeepingTheGlobalRowVisibleEverywhere() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("smtp-a");
        UUID orgB = newOrg("smtp-b");
        String globalHost = "smtp.global-" + s + ".example";
        String aHost = "smtp.a-" + s + ".example";
        String bHost = "smtp.b-" + s + ".example";
        seedRow(globalHost, null);
        seedRow(aHost, orgA);
        seedRow(bHost, orgB);

        try (Connection probe = appRoleConnection()) {
            // Org A's context: its own row + the global row, NEVER org B's.
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, aHost)).isTrue();
            assertThat(visible(probe, globalHost)).isTrue();
            assertThat(visible(probe, bHost)).isFalse();

            // Org B's context: its own row + the global row, NEVER org A's.
            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, bHost)).isTrue();
            assertThat(visible(probe, globalHost)).isTrue();
            assertThat(visible(probe, aHost)).isFalse();

            // No context (pre-org window / seeder): ONLY the global row — every tenant relay fails closed.
            resetContext(probe);
            assertThat(visible(probe, globalHost)).isTrue();
            assertThat(visible(probe, aHost)).isFalse();
            assertThat(visible(probe, bHost)).isFalse();

            // Platform: everything.
            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, aHost)).isTrue();
            assertThat(visible(probe, bHost)).isTrue();
            assertThat(visible(probe, globalHost)).isTrue();
        }
    }

    @Test
    void withCheckLetsATenantWriteOnlyItsOwnRowNeverAForeignOrGlobalOne() throws SQLException {
        String s = suffix();
        // Fresh orgs with NO pre-seeded row and NO global row exist here — so a refused write fails PURELY on the
        // RLS WITH CHECK, never masked by the per-org/global partial-unique index.
        UUID orgC = newOrg("smtp-c");
        UUID orgD = newOrg("smtp-d");

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgC.toString());
            String ownHost = "smtp.w-c-" + s + ".example";
            insertRow(probe, ownHost, orgC);                                              // own row — allowed
            // Each refusal must be the RLS policy (SQLState 42501, insufficient_privilege), not an incidental
            // error — the fresh orgs guarantee no partial-unique collision can masquerade as the refusal.
            assertRlsRefuses(() -> insertRow(probe, "smtp.w-d-" + s + ".example", orgD)); // another org — refused
            assertRlsRefuses(() -> insertRow(probe, "smtp.w-g-" + s + ".example", null)); // forge global — refused
            assertRlsRefuses(() -> relabel(probe, ownHost, orgD)); // move own row cross-tenant via UPDATE — refused
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    /** Insert a row directly (owner connection bypasses RLS) so we control its tier. */
    private void seedRow(String host, UUID orgId) {
        ownerJdbc().update("insert into smtp_settings (id, org_id, host, port) values (gen_random_uuid(), ?, ?, 587)",
                orgId, host);
        cleanups.add(() -> ownerJdbc().update("delete from smtp_settings where host = ?", host));
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

    private boolean visible(Connection c, String host) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from smtp_settings where host = ?")) {
            ps.setString(1, host);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertRow(Connection c, String host, UUID orgId) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from smtp_settings where host = ?", host));
        try (PreparedStatement ps = c.prepareStatement(
                "insert into smtp_settings (id, org_id, host, port) values (gen_random_uuid(), ?, ?, 587)")) {
            ps.setObject(1, orgId);
            ps.setString(2, host);
            ps.executeUpdate();
        }
    }

    private void relabel(Connection c, String host, UUID newOrgId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("update smtp_settings set org_id = ? where host = ?")) {
            ps.setObject(1, newOrgId);
            ps.setString(2, host);
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
