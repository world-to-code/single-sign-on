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
 * Proves the org-scoping RLS on {@code federated_identity} against the application's real NON-SUPERUSER runtime
 * role ({@code sso_app}) — a superuser (the Testcontainers/dev default) bypasses RLS, so an isolation bug would
 * go unseen in an ordinary service test (whose queries are already {@code WHERE org_id = ?}-predicated).
 *
 * <p>What a leak here would mean: this table answers "which local account is this upstream identity?", so a row
 * readable across tenants lets a federated login in one organization resolve to ANOTHER organization's account.
 * The policy is STRICT per-tier, mirroring {@code identity_provider} — links are never inherited. Asserts, at
 * the storage layer:
 * <ul>
 *   <li>a tenant's link is visible ONLY in its own org's context, never another tenant's;</li>
 *   <li>no context at all (the pre-org window the login flow starts in) sees nothing;</li>
 *   <li>WITH CHECK: a tenant may write only its OWN link; a foreign org_id is refused.</li>
 * </ul>
 * Seeding/teardown use the privileged owner connection; the isolation assertions use a raw app-role connection.
 */
class FederatedIdentityRlsIT extends AbstractIntegrationTest {

    private static final String ISSUER = "https://upstream.test";

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    /** Reverse registration order: a link references an account which references its org, and app_user.org_id
     *  is NOT ON DELETE CASCADE, so teardown has to unwind in the opposite order it was built. */
    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void rlsIsolatesLinksStrictlyPerTenant() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("fed-a");
        UUID orgB = newOrg("fed-b");
        String subjectA = "sub-a-" + s;
        String subjectB = "sub-b-" + s;
        seedLink(orgA, subjectA);
        seedLink(orgB, subjectB);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, subjectA)).isTrue();
            assertThat(visible(probe, subjectB)).isFalse();

            setContext(probe, "app.current_org", orgB.toString());
            assertThat(visible(probe, subjectB)).isTrue();
            assertThat(visible(probe, subjectA)).isFalse();

            // The federated login flow runs pre-authentication; before callInOrg binds a tenant there is no
            // context at all, and every row must fail closed rather than resolve an arbitrary account.
            resetContext(probe);
            assertThat(visible(probe, subjectA)).isFalse();
            assertThat(visible(probe, subjectB)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, subjectA)).isTrue();
            assertThat(visible(probe, subjectB)).isTrue();
        }
    }

    /** The SAME upstream subject may legitimately be linked in two tenants — to two DIFFERENT local accounts. */
    @Test
    void theSameUpstreamSubjectLinksIndependentlyInEachTenant() throws SQLException {
        String sharedSubject = "shared-sub-" + suffix();
        UUID orgA = newOrg("fed-shared-a");
        UUID orgB = newOrg("fed-shared-b");
        UUID userA = seedLink(orgA, sharedSubject);
        UUID userB = seedLink(orgB, sharedSubject); // the key is (org_id, issuer, subject), so this is fine

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(linkedUser(probe, sharedSubject)).isEqualTo(userA);

            setContext(probe, "app.current_org", orgB.toString());
            assertThat(linkedUser(probe, sharedSubject)).isEqualTo(userB);
        }
    }

    /** Retiring an upstream's identities is a DELETE — it must not reach across the tenant boundary either. */
    @Test
    void aTenantsDeleteCannotRetireAnotherTenantsIdentities() throws SQLException {
        String s = suffix();
        UUID orgA = newOrg("fed-del-a");
        UUID orgB = newOrg("fed-del-b");
        String subjectA = "del-a-" + s;
        String subjectB = "del-b-" + s;
        seedLink(orgA, subjectA);
        seedLink(orgB, subjectB);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            // The org predicate is deliberately absent: RLS alone must confine the blast radius.
            assertThat(deleteByIssuer(probe)).isEqualTo(1); // only org A's row was reachable

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, subjectA)).isFalse();
            assertThat(visible(probe, subjectB)).isTrue(); // org B's identity survived
        }
    }

    @Test
    void withCheckLetsATenantWriteOnlyItsOwnLink() throws SQLException {
        String s = suffix();
        UUID orgC = newOrg("fed-c");
        UUID orgD = newOrg("fed-d");
        UUID userC = newUser(orgC);
        UUID userD = newUser(orgD);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgC.toString());
            insertLink(probe, orgC, "own-" + s, userC);                              // own row — allowed
            assertRlsRefuses(() -> insertLink(probe, orgD, "foreign-" + s, userD));   // another org — refused
            assertRlsRefuses(() -> relabel(probe, "own-" + s, orgD));                 // move it cross-tenant — refused
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    /** An org-owned account for the link to point at (app_user has no RLS; the owner connection seeds it). */
    private UUID newUser(UUID orgId) {
        UUID id = UUID.randomUUID();
        String handle = "fed-" + suffix() + "@example.test";
        ownerJdbc().update("""
                insert into app_user (id, username, email, display_name, org_id)
                values (?, ?, ?, 'Probe', ?)""", id, handle, handle, orgId);
        cleanups.add(() -> ownerJdbc().update("delete from app_user where id = ?", id));
        return id;
    }

    /** Seeds a link for {@code subject} in {@code orgId}; returns the account it points at. */
    private UUID seedLink(UUID orgId, String subject) {
        UUID userId = newUser(orgId);
        ownerJdbc().update("""
                insert into federated_identity (id, org_id, issuer, subject, provider_alias, user_id)
                values (gen_random_uuid(), ?, ?, ?, 'okta', ?)""", orgId, ISSUER, subject, userId);
        cleanups.add(() -> ownerJdbc().update("delete from federated_identity where subject = ?", subject));
        return userId;
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

    private boolean visible(Connection c, String subject) throws SQLException {
        return linkedUser(c, subject) != null;
    }

    private UUID linkedUser(Connection c, String subject) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select user_id from federated_identity where subject = ?")) {
            ps.setString(1, subject);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }

    private void insertLink(Connection c, UUID orgId, String subject, UUID userId) throws SQLException {
        cleanups.add(() -> ownerJdbc().update("delete from federated_identity where subject = ?", subject));
        try (PreparedStatement ps = c.prepareStatement("""
                insert into federated_identity (id, org_id, issuer, subject, provider_alias, user_id)
                values (gen_random_uuid(), ?, ?, ?, 'okta', ?)""")) {
            ps.setObject(1, orgId);
            ps.setString(2, ISSUER);
            ps.setString(3, subject);
            ps.setObject(4, userId);
            ps.executeUpdate();
        }
    }

    /** Deletes every link for the seeded issuer that the current context can SEE; returns the row count. */
    private int deleteByIssuer(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("delete from federated_identity where issuer = ?")) {
            ps.setString(1, ISSUER);
            return ps.executeUpdate();
        }
    }

    private void relabel(Connection c, String subject, UUID newOrgId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "update federated_identity set org_id = ? where subject = ?")) {
            ps.setObject(1, newOrgId);
            ps.setString(2, subject);
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
