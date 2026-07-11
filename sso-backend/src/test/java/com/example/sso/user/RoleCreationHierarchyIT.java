package com.example.sso.user;

import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleHierarchyService;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;

import com.example.sso.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the role-builder "attach below" wiring: a role created with parent roles becomes their child in
 * the inheritance DAG (so the parent's holders dominate — and inherit — it), while a role created with no
 * parents is a detached root. This is the mechanism that makes a role a tenant admin creates one they may
 * later assign (it sits strictly beneath their apex) without ever being able to mint one at/above their level.
 */
class RoleCreationHierarchyIT extends AbstractIntegrationTest {

    @Autowired
    RoleService roleService;
    @Autowired
    RoleHierarchyService hierarchy;
    @Autowired
    UserService userService;

    private final List<Runnable> cleanups = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void aRoleCreatedWithAParentIsWiredBelowItAndDominatedByItsHolder() {
        RoleRef parent = createRole("ROLE_P5_PARENT", Set.of(Permissions.USER_READ), Set.of());
        RoleRef child = createRole("ROLE_P5_CHILD", Set.of(Permissions.GROUP_READ), Set.of(parent.getId()));

        // The edge exists and is stamped for the creation tier (global here — no org context bound).
        Long edges = ownerJdbc().queryForObject(
                "select count(*) from role_hierarchy where parent_role_id = ? and child_role_id = ? "
                        + "and org_id is null", Long.class, parent.getId(), child.getId());
        assertThat(edges).isOne();

        UUID holder = userHolding(parent.getId());
        assertThat(hierarchy.actorMayManageRole(holder, child.getId())).isTrue(); // below the holder's role
    }

    @Test
    void aRoleCreatedWithNoParentIsADetachedRoot() {
        RoleRef root = createRole("ROLE_P5_ROOT", Set.of(Permissions.USER_READ), Set.of());

        Long edges = ownerJdbc().queryForObject(
                "select count(*) from role_hierarchy where child_role_id = ?", Long.class, root.getId());
        assertThat(edges).isZero();

        // A detached root has no parent edge, yet its holder may still manage it — it is their OWN level, not
        // a role above them.
        UUID holder = userHolding(root.getId());
        assertThat(hierarchy.actorMayManageRole(holder, root.getId())).isTrue();
    }

    @Test
    void deletingARoleRemovesItsInheritanceEdges() {
        RoleRef parent = createRole("ROLE_P5_DEL_PARENT", Set.of(Permissions.USER_READ), Set.of());
        RoleRef child = createRole("ROLE_P5_DEL_CHILD", Set.of(Permissions.GROUP_READ), Set.of(parent.getId()));
        assertThat(edgeCount(parent.getId(), child.getId())).isOne();

        roleService.delete(child.getId());

        // deleteJoinRows explicitly unlinks the role's edges (not merely the latent FK cascade) — no lingering
        // edge points at a role that no longer exists.
        assertThat(edgeCount(parent.getId(), child.getId())).isZero();
    }

    private long edgeCount(UUID parent, UUID child) {
        return ownerJdbc().queryForObject(
                "select count(*) from role_hierarchy where parent_role_id = ? and child_role_id = ?",
                Long.class, parent, child);
    }

    private RoleRef createRole(String name, Set<String> perms, Set<UUID> parents) {
        RoleRef role = roleService.create(name, perms, parents);
        cleanups.add(() -> ownerJdbc().update("delete from role where id = ?", role.getId()));
        return role;
    }

    private UUID userHolding(UUID roleId) {
        String username = "p5-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount account = userService.createUser(
                new NewUser(username, username + "@example.com", "P5", "S3cret!pw", Set.of()));
        cleanups.add(() -> userService.delete(account.getId()));
        ownerJdbc().update("insert into app_user_role (user_id, role_id) values (?, ?)", account.getId(), roleId);
        return account.getId();
    }
}
