package com.example.sso.user;

import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.role.RoleHierarchyService;

import com.example.sso.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "may manage" predicate over the role-inheritance DAG. A grandparent→parent→child chain proves the
 * "not above" semantics the assignment gates depend on: an actor may manage roles at OR below their own
 * level (their held role and everything beneath it), never one strictly above. Crucially it also pins the
 * multi-held-role case — an actor holding BOTH a low role and a high one must NOT have the high one counted
 * as "above" them (the bug that hid a tenant admin's own ORG_ADMIN and blocked its assignment). Fail-closed
 * name resolution (an unknown name is never manageable) stops a mistyped/foreign role slipping the gate.
 */
class RoleHierarchyServiceIT extends AbstractIntegrationTest {

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
    void anActorMayManageRolesAtOrBelowTheirHeldRoleButNotAbove() {
        UUID grandparent = seedGlobalRole("ROLE_P4_GRANDPARENT");
        UUID parent = seedGlobalRole("ROLE_P4_PARENT");
        UUID child = seedGlobalRole("ROLE_P4_CHILD");
        seedEdge(grandparent, parent);
        seedEdge(parent, child);
        UUID actor = userHolding(parent);

        assertThat(hierarchy.actorMayManageRole(actor, child)).isTrue();        // below
        assertThat(hierarchy.actorMayManageRole(actor, parent)).isTrue();       // their own level — NOT above
        assertThat(hierarchy.actorMayManageRole(actor, grandparent)).isFalse(); // strictly above

        assertThat(hierarchy.rolesAboveActor(actor)).containsExactly(grandparent);
    }

    @Test
    void holdingALowRoleAlongsideAHighOneDoesNotCountTheHighOneAsAbove() {
        // The real-world tenant-admin shape: a user holds ROLE_USER (low) AND ROLE_ORG_ADMIN (high). Without
        // subtracting the reachable set, ancestors(ROLE_USER) would wrongly mark ORG_ADMIN/GROUP_ADMIN as
        // "above" — hiding and blocking the admin's OWN tier roles. Here: orgAdmin→groupAdmin→user, holder of
        // BOTH user and orgAdmin sees NOTHING above them and may manage all three.
        UUID admin = seedGlobalRole("ROLE_P4_TA_ADMIN");
        UUID orgAdmin = seedGlobalRole("ROLE_P4_TA_ORGADMIN");
        UUID groupAdmin = seedGlobalRole("ROLE_P4_TA_GROUPADMIN");
        UUID user = seedGlobalRole("ROLE_P4_TA_USER");
        seedEdge(admin, orgAdmin);
        seedEdge(orgAdmin, groupAdmin);
        seedEdge(groupAdmin, user);
        UUID actor = userHolding(orgAdmin);
        ownerJdbc().update("insert into app_user_role (user_id, role_id) values (?, ?)", actor, user); // ALSO holds USER

        assertThat(hierarchy.rolesAboveActor(actor)).containsExactly(admin); // only the truly-higher ADMIN
        assertThat(hierarchy.actorMayManageRole(actor, orgAdmin)).isTrue();   // own level
        assertThat(hierarchy.actorMayManageRole(actor, groupAdmin)).isTrue(); // below
        assertThat(hierarchy.actorMayManageRole(actor, user)).isTrue();       // below
        assertThat(hierarchy.actorMayManageRole(actor, admin)).isFalse();     // above
    }

    @Test
    void aSiblingOfAHigherRoleIsNotCountedAboveTheActor() {
        // Diamond: apex → left, apex → right, left → leaf, right → leaf. An actor holding {left, leaf} has
        // apex {left}. 'right' is a SIBLING of left (a co-parent of the held leaf), not above the actor — it
        // must NOT be treated as above (the apex-based computation gets this right; a naive ancestors-of-all-
        // held walk would wrongly include it because right is an ancestor of the held leaf).
        UUID apex = seedGlobalRole("ROLE_P4_D_APEX");
        UUID left = seedGlobalRole("ROLE_P4_D_LEFT");
        UUID right = seedGlobalRole("ROLE_P4_D_RIGHT");
        UUID leaf = seedGlobalRole("ROLE_P4_D_LEAF");
        seedEdge(apex, left);
        seedEdge(apex, right);
        seedEdge(left, leaf);
        seedEdge(right, leaf);
        UUID actor = userHolding(left);
        ownerJdbc().update("insert into app_user_role (user_id, role_id) values (?, ?)", actor, leaf); // also holds leaf

        assertThat(hierarchy.rolesAboveActor(actor)).containsExactly(apex); // only the true higher role
        assertThat(hierarchy.actorMayManageRole(actor, right)).isTrue();    // sibling — not above
        assertThat(hierarchy.actorMayManageRole(actor, leaf)).isTrue();     // below
        assertThat(hierarchy.actorMayManageRole(actor, apex)).isFalse();    // above
    }

    @Test
    void nameResolutionIsFailClosedAndAtLevelIsManageable() {
        UUID parent = seedGlobalRole("ROLE_P4_NAMED_PARENT");
        UUID child = seedGlobalRole("ROLE_P4_NAMED_CHILD");
        seedEdge(parent, child);
        UUID actor = userHolding(parent);

        assertThat(hierarchy.actorMayManageRoleName(actor, "ROLE_P4_NAMED_CHILD", null)).isTrue();  // below
        assertThat(hierarchy.actorMayManageRoleName(actor, "ROLE_P4_NAMED_PARENT", null)).isTrue(); // own level
        assertThat(hierarchy.actorMayManageRoleName(actor, "ROLE_DOES_NOT_EXIST", null)).isFalse(); // fail-closed
    }

    @Test
    void anActorMayManageTheirOwnLoneRole() {
        UUID loneRole = seedGlobalRole("ROLE_P4_LONE");
        UUID actor = userHolding(loneRole);

        assertThat(hierarchy.actorMayManageRole(actor, loneRole)).isTrue(); // their own level, nothing above
        assertThat(hierarchy.rolesAboveActor(actor)).isEmpty();
    }

    private UUID userHolding(UUID roleId) {
        String username = "p4-" + suffix();
        UserAccount account = userService.createUser(
                new NewUser(username, username + "@example.com", "P4", "S3cret!pw", Set.of()));
        cleanups.add(() -> userService.delete(account.getId()));
        ownerJdbc().update("insert into app_user_role (user_id, role_id) values (?, ?)", account.getId(), roleId);
        return account.getId();
    }

    private UUID seedGlobalRole(String name) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into role (id, name, system) values (?, ?, false)", id, name);
        cleanups.add(() -> ownerJdbc().update("delete from role where id = ?", id));
        return id;
    }

    private void seedEdge(UUID parent, UUID child) {
        ownerJdbc().update(
                "insert into role_hierarchy (parent_role_id, child_role_id, org_id) values (?, ?, null)",
                parent, child);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
