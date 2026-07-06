package com.example.sso.resource;

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
 * Proves the org-scoping RLS on the resource DAG ({@code resource} + the child {@code resource_edge} /
 * {@code resource_member} / {@code resource_role}) against the application's real NON-SUPERUSER runtime role
 * ({@code sso_app}) — a superuser bypasses RLS. Global (org NULL) rows are visible in every context; a
 * tenant's rows only in its org's context (or platform); WITH CHECK refuses a tenant-bound connection writing
 * a global row or another org's row. Because the recursive subtree CTEs (ResourceRepository) walk
 * {@code resource_edge}/{@code resource_role} directly, isolating those tables is what confines delegated-admin
 * reach to one tenant. {@code resource_type} stays GLOBAL (a shared vocabulary). Not {@code @Transactional}.
 */
class ResourceRlsIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // LIFO: delete children before the parents/types they reference (FK-safe teardown).
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void rlsIsolatesTenantResourcesWhileKeepingGlobalOnesVisibleEverywhere() throws SQLException {
        UUID orgA = newOrg("res-a");
        UUID orgB = newOrg("res-b");
        UUID type = seedType();
        UUID global = seedResource(type, null);
        UUID a = seedResource(type, orgA);
        UUID b = seedResource(type, orgB);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(visible(probe, "resource", global)).isTrue();
            assertThat(visible(probe, "resource", a)).isTrue();
            assertThat(visible(probe, "resource", b)).isFalse();

            resetContext(probe); // no context: only global resources resolve (fail-closed)
            assertThat(visible(probe, "resource", global)).isTrue();
            assertThat(visible(probe, "resource", a)).isFalse();

            setContext(probe, "app.platform", "on");
            assertThat(visible(probe, "resource", a)).isTrue();
            assertThat(visible(probe, "resource", b)).isTrue();

            // WITH CHECK: a tenant-bound connection may write its own org, but not a global nor another org.
            // resetContext first so the leftover app.platform='on' above does not open the platform branch.
            resetContext(probe);
            setContext(probe, "app.current_org", orgA.toString());
            insertResource(probe, type, orgA); // allowed
            assertThatThrownBy(() -> insertResource(probe, type, orgB)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertResource(probe, type, null)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void rlsIsolatesTheChildEdgeMemberAndRoleTables() throws SQLException {
        UUID orgA = newOrg("reschild-a");
        UUID orgB = newOrg("reschild-b");
        UUID type = seedType();
        UUID parent = seedResource(type, orgA);
        UUID child = seedResource(type, orgA);
        UUID user = ownerJdbc().queryForObject("select id from app_user limit 1", UUID.class); // resource_role FK
        // edge / member / role rows owned by org A (cascade-deleted with their resource, so no explicit cleanup)
        owner("insert into resource_edge (parent_id, child_id, org_id) values (?, ?, ?)", parent, child, orgA);
        owner("insert into resource_member (resource_id, member_type, member_id, org_id) values (?, 'USER', ?, ?)",
                parent, UUID.randomUUID().toString(), orgA);
        owner("insert into resource_role (resource_id, user_id, tier, org_id) values (?, ?, 'ADMIN', ?)",
                parent, user, orgA);

        try (Connection probe = appRoleConnection()) {
            setContext(probe, "app.current_org", orgA.toString());
            assertThat(count(probe, "select count(*) from resource_edge where parent_id = '" + parent + "'")).isOne();
            assertThat(count(probe, "select count(*) from resource_member where resource_id = '" + parent + "'")).isOne();
            assertThat(count(probe, "select count(*) from resource_role where resource_id = '" + parent + "'")).isOne();

            setContext(probe, "app.current_org", orgB.toString()); // another tenant sees none of A's graph
            assertThat(count(probe, "select count(*) from resource_edge where parent_id = '" + parent + "'")).isZero();
            assertThat(count(probe, "select count(*) from resource_member where resource_id = '" + parent + "'")).isZero();
            assertThat(count(probe, "select count(*) from resource_role where resource_id = '" + parent + "'")).isZero();
        }
    }

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private UUID seedType() {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into resource_type (id, name) values (?, ?)", id, "RLS-TYPE-" + suffix());
        cleanups.add(() -> ownerJdbc().update("delete from resource_type where id = ?", id));
        return id;
    }

    private UUID seedResource(UUID typeId, UUID orgId) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into resource (id, name, type_id, org_id) values (?, ?, ?, ?)",
                id, "R-" + suffix(), typeId, orgId);
        cleanups.add(() -> ownerJdbc().update("delete from resource where id = ?", id));
        return id;
    }

    private void owner(String sql, Object... args) {
        ownerJdbc().update(sql, args);
    }

    private void insertResource(Connection c, UUID typeId, UUID orgId) throws SQLException {
        UUID id = UUID.randomUUID();
        cleanups.add(() -> ownerJdbc().update("delete from resource where id = ?", id));
        try (PreparedStatement ps = c.prepareStatement(
                "insert into resource (id, name, type_id, org_id) values (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "R-" + suffix());
            ps.setObject(3, typeId);
            ps.setObject(4, orgId);
            ps.executeUpdate();
        }
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

    private boolean visible(Connection c, String table, UUID id) throws SQLException {
        return count(c, "select count(*) from " + table + " where id = '" + id + "'") == 1;
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
