package com.example.sso.user;

import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Editing a role's inheritance (post-creation, via {@link RoleService#setInheritsFrom}): a role gains the
 * effective permissions of a role it starts inheriting, loses them when the edge is removed, and a rewire
 * that would close a cycle is refused. Proves the DAG mechanics + the cycle guard against a real DB (RLS).
 */
class RoleInheritanceEditIT extends AbstractIntegrationTest {

    @Autowired
    RoleService roleService;

    private final List<Runnable> cleanups = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void inheritingARoleGrantsItsEffectivePermissions() {
        RoleRef a = createRole("ROLE_EDIT_A", Set.of(Permissions.USER_READ));
        RoleRef b = createRole("ROLE_EDIT_B", Set.of(Permissions.GROUP_READ));

        roleService.setInheritsFrom(a.getId(), Set.of(b.getId()));

        assertThat(edgeCount(a.getId(), b.getId())).isOne();
        // A now effectively carries B's permission (GROUP_READ) even though it was never directly assigned it.
        assertThat(roleService.effectivePermissionNames(Set.of(a.getId())))
                .contains(Permissions.USER_READ, Permissions.GROUP_READ);
    }

    @Test
    void removingInheritanceDropsTheInheritedPermissions() {
        RoleRef a = createRole("ROLE_EDIT_RM_A", Set.of(Permissions.USER_READ));
        RoleRef b = createRole("ROLE_EDIT_RM_B", Set.of(Permissions.GROUP_READ));
        roleService.setInheritsFrom(a.getId(), Set.of(b.getId()));
        assertThat(edgeCount(a.getId(), b.getId())).isOne();

        roleService.setInheritsFrom(a.getId(), Set.of());

        assertThat(edgeCount(a.getId(), b.getId())).isZero();
        assertThat(roleService.effectivePermissionNames(Set.of(a.getId())))
                .contains(Permissions.USER_READ).doesNotContain(Permissions.GROUP_READ);
    }

    @Test
    void inheritingARoleThatAlreadyInheritsYouIsRefusedAsACycle() {
        RoleRef a = createRole("ROLE_EDIT_CYC_A", Set.of(Permissions.USER_READ));
        RoleRef b = createRole("ROLE_EDIT_CYC_B", Set.of(Permissions.GROUP_READ));
        roleService.setInheritsFrom(a.getId(), Set.of(b.getId())); // A inherits B

        // B may not now inherit A — that would close a cycle (A→B→A).
        assertThatThrownBy(() -> roleService.setInheritsFrom(b.getId(), Set.of(a.getId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(edgeCount(b.getId(), a.getId())).isZero();
    }

    private long edgeCount(UUID parent, UUID child) {
        return ownerJdbc().queryForObject(
                "select count(*) from role_hierarchy where parent_role_id = ? and child_role_id = ?",
                Long.class, parent, child);
    }

    private RoleRef createRole(String name, Set<String> perms) {
        RoleRef role = roleService.create(name, perms);
        cleanups.add(() -> ownerJdbc().update("delete from role where id = ?", role.getId()));
        return role;
    }
}
