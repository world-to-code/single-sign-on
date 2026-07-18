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
 * Proves the org-scoping RLS on {@code org_branding} against the real NON-SUPERUSER runtime role
 * ({@code sso_app}). A tenant's branding row is visible only in its own org's context (or platform); the
 * platform-default row (org_id NULL) is visible everywhere (so own-else-global resolution works inside a tenant
 * context); WITH CHECK refuses a tenant writing another org's row or forging the global default. Branding is
 * public, but it still keys on org_id and isolates writes.
 */
class BrandingRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void rlsIsolatesTenantBrandingWhileKeepingTheGlobalRowVisibleEverywhere() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("brand-a");
        UUID orgB = newOrg("brand-b");
        String globalName = "global-" + s;
        String aName = "a-" + s;
        String bName = "b-" + s;
        seedRow(globalName, null);
        seedRow(aName, orgA);
        seedRow(bName, orgB);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, aName)).isTrue();
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, bName)).isFalse();

            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, bName)).isTrue();
            assertThat(visible(probe, globalName)).isTrue();
            assertThat(visible(probe, aName)).isFalse();

            resetContext(probe);
            assertThat(visible(probe, globalName)).isTrue(); // only the global row with no context
            assertThat(visible(probe, aName)).isFalse();
            assertThat(visible(probe, bName)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, aName)).isTrue();
            assertThat(visible(probe, bName)).isTrue();
        }
    }

    @Test
    void withCheckLetsATenantWriteOnlyItsOwnRowNeverAForeignOrGlobalOne() throws SQLException {
        String s = suffix();
        UUID orgC = newOrg("brand-c");
        UUID orgD = newOrg("brand-d");

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgC.toString());
            String ownName = "w-c-" + s;
            insertRow(probe, ownName, orgC);                                       // own row — allowed
            assertRlsRefuses(() -> insertRow(probe, "w-d-" + s, orgD));            // another org — refused
            assertRlsRefuses(() -> insertRow(probe, "w-g-" + s, null));            // forge global — refused
            assertRlsRefuses(() -> relabel(probe, ownName, orgD));                 // move own row cross-tenant — refused
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private void seedRow(String productName, UUID orgId) {
        ownerJdbc().update("insert into org_branding (id, org_id, product_name) "
                + "values (gen_random_uuid(), ?, ?)", orgId, productName);
        cleanups.add(() -> ownerJdbc().update("delete from org_branding where product_name = ?", productName));
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

    private boolean visible(Connection c, String productName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from org_branding where product_name = ?")) {
            ps.setString(1, productName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertRow(Connection c, String productName, UUID orgId) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from org_branding where product_name = ?", productName));
        try (PreparedStatement ps = c.prepareStatement("insert into org_branding (id, org_id, product_name) "
                + "values (gen_random_uuid(), ?, ?)")) {
            ps.setObject(1, orgId);
            ps.setString(2, productName);
            ps.executeUpdate();
        }
    }

    private void relabel(Connection c, String productName, UUID newOrgId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("update org_branding set org_id = ? where product_name = ?")) {
            ps.setObject(1, newOrgId);
            ps.setString(2, productName);
            ps.executeUpdate();
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
