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
 * Proves the org-scoping RLS on {@code email_template} against the real NON-SUPERUSER runtime role
 * ({@code sso_app}). A tenant's branded template is visible only in its own org's context (or platform); the
 * platform-default row (org_id NULL) is visible everywhere (so own-else-global resolution works inside a tenant
 * context); and WITH CHECK refuses a tenant writing another org's row or forging the global default. Seeding
 * uses the privileged owner; the assertions use a raw {@link #appRoleConnection()}. Not {@code @Transactional}.
 */
class EmailTemplateRlsIT extends AbstractIntegrationTest {

    private static final String EVENT = "EMAIL_VERIFICATION_CODE";

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void rlsIsolatesTenantTemplatesWhileKeepingTheGlobalRowVisibleEverywhere() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("tmpl-a");
        UUID orgB = newOrg("tmpl-b");
        String globalSubject = "global-" + s;
        String aSubject = "a-" + s;
        String bSubject = "b-" + s;
        seedRow(globalSubject, null);
        seedRow(aSubject, orgA);
        seedRow(bSubject, orgB);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, aSubject)).isTrue();
            assertThat(visible(probe, globalSubject)).isTrue();
            assertThat(visible(probe, bSubject)).isFalse();

            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, bSubject)).isTrue();
            assertThat(visible(probe, globalSubject)).isTrue();
            assertThat(visible(probe, aSubject)).isFalse();

            resetContext(probe);
            assertThat(visible(probe, globalSubject)).isTrue(); // only the global row with no context
            assertThat(visible(probe, aSubject)).isFalse();
            assertThat(visible(probe, bSubject)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, aSubject)).isTrue();
            assertThat(visible(probe, bSubject)).isTrue();
            assertThat(visible(probe, globalSubject)).isTrue();
        }
    }

    @Test
    void withCheckLetsATenantWriteOnlyItsOwnRowNeverAForeignOrGlobalOne() throws SQLException {
        String s = suffix();
        UUID orgC = newOrg("tmpl-c");
        UUID orgD = newOrg("tmpl-d");

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgC.toString());
            String ownSubject = "w-c-" + s;
            insertRow(probe, ownSubject, orgC);                                    // own row — allowed
            assertRlsRefuses(() -> insertRow(probe, "w-d-" + s, orgD));            // another org — refused
            assertRlsRefuses(() -> insertRow(probe, "w-g-" + s, null));            // forge global — refused
            assertRlsRefuses(() -> relabel(probe, ownSubject, orgD));              // move own row cross-tenant — refused
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private void seedRow(String subject, UUID orgId) {
        ownerJdbc().update("insert into email_template (id, org_id, event, subject, html_body) "
                + "values (gen_random_uuid(), ?, ?, ?, '<p>x</p>')", orgId, EVENT, subject);
        cleanups.add(() -> ownerJdbc().update("delete from email_template where subject = ?", subject));
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

    private boolean visible(Connection c, String subject) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from email_template where subject = ?")) {
            ps.setString(1, subject);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void insertRow(Connection c, String subject, UUID orgId) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from email_template where subject = ?", subject));
        try (PreparedStatement ps = c.prepareStatement("insert into email_template (id, org_id, event, subject, "
                + "html_body) values (gen_random_uuid(), ?, ?, ?, '<p>x</p>')")) {
            ps.setObject(1, orgId);
            ps.setString(2, EVENT);
            ps.setString(3, subject);
            ps.executeUpdate();
        }
    }

    private void relabel(Connection c, String subject, UUID newOrgId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("update email_template set org_id = ? where subject = ?")) {
            ps.setObject(1, newOrgId);
            ps.setString(2, subject);
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
