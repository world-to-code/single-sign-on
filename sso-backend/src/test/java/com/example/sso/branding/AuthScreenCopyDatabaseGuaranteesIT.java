package com.example.sso.branding;

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
 * The guarantees {@code auth_screen_copy} leans on that live in the DATABASE, exercised by a writer that goes
 * AROUND the service — because a writer that goes around the service is the only thing they exist for.
 *
 * <p>Every one of these was asserted by nothing until a test-quality review said so, while the service tests
 * "proved" isolation with queries already keyed on {@code org_id} (which cannot return another tenant's rows
 * whether or not RLS exists) and "proved" one-row-per-screen through the service's own check-then-act. Delete
 * the policy, either partial index, or the whole of V146, and that suite stays green.
 *
 * <ul>
 *   <li><b>RLS</b> — against the real non-superuser runtime role, since a superuser bypasses it entirely.</li>
 *   <li><b>Tier-aware partial unique indexes</b> — the invariant behind a check-then-act no sequential test
 *       can cover ({@code db-invariants}: the constraint is what holds under two concurrent writers).</li>
 *   <li><b>V145/V146 CHECKs</b> — the https scheme and the closed screen set. V146 exists precisely so a
 *       migration, a manual fix-up or a future writer cannot put {@code javascript:} in front of every
 *       pre-authentication visitor, so only a bypassing writer can test it.</li>
 * </ul>
 */
class AuthScreenCopyDatabaseGuaranteesIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    // --- RLS -------------------------------------------------------------------------------------------

    @Test
    void rlsIsolatesTenantWordingWhileKeepingTheGlobalRowVisibleEverywhere() throws SQLException {
        UUID orgA = newOrg("copy-rls-a");
        UUID orgB = newOrg("copy-rls-b");
        String globalHeadline = "global-" + suffix();
        String aHeadline = "a-" + suffix();
        String bHeadline = "b-" + suffix();
        seedRow(null, "LOGIN", globalHeadline);
        seedRow(orgA, "LOGIN", aHeadline);
        seedRow(orgB, "LOGIN", bHeadline);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, aHeadline)).isTrue();
            // Must stay visible: per-field inheritance reads the platform row from INSIDE a tenant's context,
            // so a policy that hid it would silently drop every tenant's inherited wording.
            assertThat(visible(probe, globalHeadline)).isTrue();
            assertThat(visible(probe, bHeadline)).isFalse();

            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, bHeadline)).isTrue();
            assertThat(visible(probe, aHeadline)).isFalse();

            resetContext(probe);
            assertThat(visible(probe, globalHeadline)).isTrue();
            assertThat(visible(probe, aHeadline)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, aHeadline)).isTrue();
            assertThat(visible(probe, bHeadline)).isTrue();
        }
    }

    @Test
    void withCheckLetsATenantWriteOnlyItsOwnRowNeverAForeignOrGlobalOne() throws SQLException {
        UUID orgC = newOrg("copy-rls-c");
        UUID orgD = newOrg("copy-rls-d");

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgC.toString());
            String own = "w-c-" + suffix();
            insertRow(probe, orgC, "LOGIN", own);                                  // own row — allowed
            assertRefused(() -> insertRow(probe, orgD, "LOGIN", "w-d-" + suffix()));   // another org
            assertRefused(() -> insertRow(probe, null, "MFA", "w-g-" + suffix()));     // forge the global row
            assertRefused(() -> relabel(probe, own, orgD));                            // move it cross-tenant
        }
    }

    // --- tier-aware partial unique indexes --------------------------------------------------------------

    @Test
    void aTenantCannotHoldTwoRowsForTheSameScreen() {
        UUID org = newOrg("copy-uq-tenant");
        seedRow(org, "LOGIN", "first-" + suffix());

        assertThatThrownBy(() -> seedRow(org, "LOGIN", "second-" + suffix()))
                .hasMessageContaining("uq_auth_screen_copy_org");
    }

    /** The same screen in ANOTHER tenant is a different row — the index is per tier, not global. */
    @Test
    void twoTenantsMayEachHoldTheSameScreen() {
        UUID orgA = newOrg("copy-uq-a");
        UUID orgB = newOrg("copy-uq-b");

        seedRow(orgA, "LOGIN", "a-" + suffix());
        seedRow(orgB, "LOGIN", "b-" + suffix());
    }

    /**
     * The global half. A plain UNIQUE over a nullable org_id would NOT catch this — NULLs do not collide — so
     * the platform tier could accumulate several rows per screen and resolution would pick one at random.
     */
    @Test
    void thePlatformTierCannotHoldTwoRowsForTheSameScreen() {
        seedRow(null, "STEPUP", "first-" + suffix());

        assertThatThrownBy(() -> seedRow(null, "STEPUP", "second-" + suffix()))
                .hasMessageContaining("uq_auth_screen_copy_global");
    }

    // --- CHECK constraints ------------------------------------------------------------------------------

    /**
     * V146's whole reason for existing: the https guarantee used to live only in the service, and every reader
     * downstream — resolve() to the public endpoint to an {@code <a href>} on the pre-authentication screen —
     * trusts the row without re-checking.
     */
    @Test
    void aNonHttpsHelpUrlIsRefusedByTheDatabase() {
        UUID org = newOrg("copy-ck-url");

        assertThatThrownBy(() -> seedRow(org, "LOGIN", "h-" + suffix(), "javascript:alert(1)"))
                .hasMessageContaining("ck_auth_screen_copy_help_url_https");
        assertThatThrownBy(() -> seedRow(org, "MFA", "h2-" + suffix(), "http://help.example"))
                .hasMessageContaining("ck_auth_screen_copy_help_url_https");
    }

    @Test
    void anHttpsHelpUrlIsAccepted() {
        UUID org = newOrg("copy-ck-ok");

        seedRow(org, "LOGIN", "h-" + suffix(), "https://help.example");
    }

    /** The screen set is closed in the database too, not only in the enum a Java writer happens to use. */
    @Test
    void aScreenOutsideTheClosedSetIsRefusedByTheDatabase() {
        UUID org = newOrg("copy-ck-screen");

        assertThatThrownBy(() -> seedRow(org, "NOT_A_SCREEN", "s-" + suffix()))
                .hasMessageContaining("auth_screen_copy_screen_check");
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private void seedRow(UUID orgId, String screen, String headline) {
        seedRow(orgId, screen, headline, null);
    }

    private void seedRow(UUID orgId, String screen, String headline, String helpUrl) {
        ownerJdbc().update("insert into auth_screen_copy (id, org_id, screen, headline, help_url) "
                + "values (gen_random_uuid(), ?, ?, ?, ?)", orgId, screen, headline, helpUrl);
        cleanups.add(() -> ownerJdbc().update("delete from auth_screen_copy where headline = ?", headline));
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

    private boolean visible(Connection c, String headline) throws SQLException {
        try (PreparedStatement ps =
                     c.prepareStatement("select count(*) from auth_screen_copy where headline = ?")) {
            ps.setString(1, headline);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertRow(Connection c, UUID orgId, String screen, String headline) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from auth_screen_copy where headline = ?", headline));
        try (PreparedStatement ps = c.prepareStatement(
                "insert into auth_screen_copy (id, org_id, screen, headline) values (gen_random_uuid(), ?, ?, ?)")) {
            ps.setObject(1, orgId);
            ps.setString(2, screen);
            ps.setString(3, headline);
            ps.executeUpdate();
        }
    }

    private void relabel(Connection c, String headline, UUID newOrgId) throws SQLException {
        try (PreparedStatement ps =
                     c.prepareStatement("update auth_screen_copy set org_id = ? where headline = ?")) {
            ps.setObject(1, newOrgId);
            ps.setString(2, headline);
            ps.executeUpdate();
        }
    }

    /** RLS refuses a WITH CHECK violation with SQLState 42501 — distinct from a CHECK constraint's 23514. */
    private void assertRefused(ThrowingCallable write) {
        assertThatThrownBy(write).isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
