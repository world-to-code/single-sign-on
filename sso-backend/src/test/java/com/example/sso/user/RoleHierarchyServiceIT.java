package com.example.sso.user;

import com.example.sso.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dominance predicate over the role-inheritance DAG. A grandparent→parent→child chain with a user
 * holding the PARENT proves the strict-below semantics the assignment gates depend on: the actor dominates
 * only the roles beneath them (child), never a peer (their own parent role) and never one above
 * (grandparent). Also pins fail-closed name resolution (an unknown name is never dominated) — the property
 * that stops a mistyped/foreign role from slipping past the "may I assign this?" check.
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
    void anActorDominatesOnlyRolesStrictlyBelowTheirHeldRole() {
        UUID grandparent = seedGlobalRole("ROLE_P4_GRANDPARENT");
        UUID parent = seedGlobalRole("ROLE_P4_PARENT");
        UUID child = seedGlobalRole("ROLE_P4_CHILD");
        seedEdge(grandparent, parent);
        seedEdge(parent, child);
        UUID actor = userHolding(parent);

        assertThat(hierarchy.actorDominatesRole(actor, child)).isTrue();
        assertThat(hierarchy.actorDominatesRole(actor, parent)).isFalse();      // peer (own role) — strict
        assertThat(hierarchy.actorDominatesRole(actor, grandparent)).isFalse(); // above the actor

        assertThat(hierarchy.rolesAboveActor(actor)).containsExactly(grandparent);
    }

    @Test
    void nameResolutionIsFailClosedAndTierAware() {
        UUID parent = seedGlobalRole("ROLE_P4_NAMED_PARENT");
        UUID child = seedGlobalRole("ROLE_P4_NAMED_CHILD");
        seedEdge(parent, child);
        UUID actor = userHolding(parent);

        assertThat(hierarchy.actorDominatesRoleName(actor, "ROLE_P4_NAMED_CHILD", null)).isTrue();
        assertThat(hierarchy.actorDominatesRoleName(actor, "ROLE_P4_NAMED_PARENT", null)).isFalse(); // peer
        assertThat(hierarchy.actorDominatesRoleName(actor, "ROLE_DOES_NOT_EXIST", null)).isFalse();  // fail-closed
    }

    @Test
    void anActorWithNoInheritingRoleDominatesNothing() {
        UUID loneRole = seedGlobalRole("ROLE_P4_LONE");
        UUID actor = userHolding(loneRole);

        assertThat(hierarchy.actorDominatesRole(actor, loneRole)).isFalse();
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
