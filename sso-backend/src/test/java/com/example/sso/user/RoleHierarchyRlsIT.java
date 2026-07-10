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
 * Proves the org-scoping RLS on {@code role_hierarchy} (the role-inheritance DAG) against the application's
 * real NON-SUPERUSER runtime role ({@code sso_app}) — a superuser bypasses RLS. A global (org NULL) edge is
 * visible in every context; a tenant's edge only in its org's context (or platform); the TIGHTENED WITH
 * CHECK refuses a tenant-bound connection minting a global edge or another org's edge. The cross-tier seed
 * edge (global ROLE_ADMIN -> a tenant's ROLE_ORG_ADMIN) is stamped with the CHILD's org, so this isolation
 * is exactly what keeps one tenant's inheritance graph from being walked (or altered) by another. It also
 * pins the self-loop CHECK. NOT {@code @Transactional} — an ambient tx would mask the real connection RLS.
 */
class RoleHierarchyRlsIT extends AbstractIntegrationTest {

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
    void rlsIsolatesTenantEdgesWhileKeepingGlobalOnesVisibleEverywhere() throws SQLException {
        UUID orgA = newOrg("rh-a");
        UUID orgB = newOrg("rh-b");
        UUID parent = seedRole();          // shared parent endpoint (PK is the parent/child pair)
        UUID cGlobal = seedRole();
        UUID cA = seedRole();
        UUID cB = seedRole();
        seedEdge(parent, cGlobal, null);
        seedEdge(parent, cA, orgA);
        seedEdge(parent, cB, orgB);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(edgeVisible(probe, parent, cGlobal)).isTrue();
            assertThat(edgeVisible(probe, parent, cA)).isTrue();
            assertThat(edgeVisible(probe, parent, cB)).isFalse();

            resetContext(probe); // no context: only global edges resolve (fail-closed)
            assertThat(edgeVisible(probe, parent, cGlobal)).isTrue();
            assertThat(edgeVisible(probe, parent, cA)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(edgeVisible(probe, parent, cA)).isTrue();
            assertThat(edgeVisible(probe, parent, cB)).isTrue();

            // WITH CHECK: a tenant-bound connection may write its own org, but not a global nor another org.
            resetContext(probe);
            setContext(probe, "app.current_org", orgA.toString());
            UUID cA2 = seedRole();
            insertEdge(probe, parent, cA2, orgA); // allowed
            UUID cRejected = seedRole();
            assertThatThrownBy(() -> insertEdge(probe, parent, cRejected, orgB)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertEdge(probe, parent, cRejected, null)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void aSelfLoopEdgeIsRejectedByTheCheckConstraint() {
        UUID role = seedRole();
        // The owner connection bypasses RLS but NOT the CHECK — a role can never be its own parent.
        assertThatThrownBy(() -> ownerJdbc().update(
                "insert into role_hierarchy (parent_role_id, child_role_id, org_id) values (?, ?, null)",
                role, role))
                .hasMessageContaining("role_hierarchy_no_self_loop");
    }

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private UUID seedRole() {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into role (id, name, system) values (?, ?, false)", id, "RH-" + suffix());
        cleanups.add(() -> ownerJdbc().update("delete from role where id = ?", id));
        return id;
    }

    private void seedEdge(UUID parent, UUID child, UUID orgId) {
        ownerJdbc().update("insert into role_hierarchy (parent_role_id, child_role_id, org_id) values (?, ?, ?)",
                parent, child, orgId);
        cleanups.add(() -> ownerJdbc().update(
                "delete from role_hierarchy where parent_role_id = ? and child_role_id = ?", parent, child));
    }

    private void insertEdge(Connection c, UUID parent, UUID child, UUID orgId) throws SQLException {
        cleanups.add(() -> ownerJdbc().update(
                "delete from role_hierarchy where parent_role_id = ? and child_role_id = ?", parent, child));
        try (PreparedStatement ps = c.prepareStatement(
                "insert into role_hierarchy (parent_role_id, child_role_id, org_id) values (?, ?, ?)")) {
            ps.setObject(1, parent);
            ps.setObject(2, child);
            ps.setObject(3, orgId);
            ps.executeUpdate();
        }
    }

    private boolean edgeVisible(Connection c, UUID parent, UUID child) throws SQLException {
        return count(c, "select count(*) from role_hierarchy where parent_role_id = '" + parent
                + "' and child_role_id = '" + child + "'") == 1;
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

    private long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
