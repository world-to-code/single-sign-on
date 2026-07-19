package com.example.sso.metadata;

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
 * Proves the org scoping on {@code attribute_definition} against the application's real NON-SUPERUSER role — a
 * superuser bypasses RLS, so an isolation bug would go unseen in a service test whose queries already carry a
 * {@code WHERE org_id} predicate.
 *
 * <p>What a leak here would mean: a definition decides whether an attribute is a directory's to own or an
 * administrator's to edit. Reading another tenant's schema discloses how they model their people; WRITING one
 * would let a tenant flip another's attribute to LOCAL and take over values a directory is supposed to own.
 * The policy is STRICT per-tier, like {@code identity_provider} and unlike {@code entity_attribute} itself: a
 * profile schema is not inherited from the platform tier.
 */
class AttributeDefinitionRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void aTenantSeesOnlyItsOwnSchemaAndNeverThePlatformTiers() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("attr-a");
        UUID orgB = newOrg("attr-b");
        String globalKey = "global-" + s;
        String aKey = "a-" + s;
        String bKey = "b-" + s;
        seed(globalKey, null);
        seed(aKey, orgA);
        seed(bKey, orgB);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, aKey)).isTrue();
            assertThat(visible(probe, bKey)).isFalse();
            assertThat(visible(probe, globalKey)).isFalse(); // not inherited

            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, bKey)).isTrue();
            assertThat(visible(probe, aKey)).isFalse();

            resetContext(probe);
            assertThat(visible(probe, aKey)).isFalse();
            assertThat(visible(probe, globalKey)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, globalKey)).isTrue();
            assertThat(visible(probe, aKey)).isTrue();
        }
    }

    @Test
    void aTenantMayDefineOnlyItsOwnSchema() throws SQLException {
        String s = suffix();
        UUID orgC = newOrg("attr-c");
        UUID orgD = newOrg("attr-d");

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgC.toString());
            insert(probe, "own-" + s, orgC);                                  // own tier — allowed
            assertRlsRefuses(() -> insert(probe, "foreign-" + s, orgD));      // another tenant — refused
            assertRlsRefuses(() -> insert(probe, "forged-" + s, null));       // forge a platform row — refused
            // Re-tiering an own row would move a definition — and its ownership rules — into another tenant.
            assertRlsRefuses(() -> retier(probe, "own-" + s, orgD));
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private void seed(String key, UUID orgId) {
        ownerJdbc().update("""
                insert into attribute_definition
                    (id, org_id, entity_kind, attr_key, display_name, data_type)
                values (gen_random_uuid(), ?, 'USER', ?, 'Probe', 'STRING')""", orgId, key);
        cleanups.add(() -> ownerJdbc().update("delete from attribute_definition where attr_key = ?", key));
    }

    private void insert(Connection c, String key, UUID orgId) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from attribute_definition where attr_key = ?", key));
        try (PreparedStatement ps = c.prepareStatement("""
                insert into attribute_definition
                    (id, org_id, entity_kind, attr_key, display_name, data_type)
                values (gen_random_uuid(), ?, 'USER', ?, 'Probe', 'STRING')""")) {
            ps.setObject(1, orgId);
            ps.setString(2, key);
            ps.executeUpdate();
        }
    }

    private void retier(Connection c, String key, UUID newOrgId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "update attribute_definition set org_id = ? where attr_key = ?")) {
            ps.setObject(1, newOrgId);
            ps.setString(2, key);
            ps.executeUpdate();
        }
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
        try (PreparedStatement ps = c.prepareStatement(
                "select count(*) from attribute_definition where attr_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
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
