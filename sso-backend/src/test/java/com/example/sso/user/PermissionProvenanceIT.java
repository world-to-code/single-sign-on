package com.example.sso.user;

import com.example.sso.admin.internal.user.application.UserDetailAdminService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.PermissionExplanation;
import com.example.sso.user.rbac.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which role actually conferred a permission — the other half of "why".
 *
 * <p>Knowing a user holds {@code user:delete} is not enough to take it away. The role that carries it may be
 * one they hold directly, or one a role they hold INHERITS from, and those are removed in different places.
 * An administrator told only "they have it" revokes the obvious role and finds the permission still there.
 *
 * <p>The answer costs no query: the role/permission rows the resolver already reads carry both ids and it was
 * discarding the role. These tests exist because "already in hand" is only true while somebody keeps it that
 * way — the next edit to that stream would otherwise silently drop provenance again.
 */
class PermissionProvenanceIT extends AbstractIntegrationTest {

    @Autowired
    UserService userService;
    @Autowired
    UserDetailAdminService userDetail;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    private Optional<PermissionExplanation> explanationOf(UUID userId, String permission) {
        return userService.explainPermissions(userId).stream()
                .filter(explanation -> explanation.permission().equals(permission))
                .findFirst();
    }

    @Test
    void aPermissionFromADirectlyHeldRoleNamesThatRole() {
        UUID role = role("ROLE_PROV_DIRECT", Permissions.USER_READ);
        UserAccount user = user();
        assign(user.getId(), role);

        assertThat(explanationOf(user.getId(), Permissions.USER_READ))
                .hasValueSatisfying(explanation ->
                        assertThat(explanation.conferredBy()).containsExactly("ROLE_PROV_DIRECT"));
    }

    /**
     * The case that makes this worth building. The permission arrives from a role the user does NOT hold —
     * revoking the role they DO hold is the obvious move and the wrong one, and nothing on screen said so.
     */
    @Test
    void aPermissionInheritedDownTheDagNamesTheRoleThatActuallyCarriesIt() {
        UUID parent = role("ROLE_PROV_PARENT");
        UUID child = role("ROLE_PROV_CHILD", Permissions.GROUP_READ);
        inherit(parent, child);
        UserAccount user = user();
        assign(user.getId(), parent);

        assertThat(explanationOf(user.getId(), Permissions.GROUP_READ))
                .hasValueSatisfying(explanation ->
                        assertThat(explanation.conferredBy()).containsExactly("ROLE_PROV_CHILD"));
    }

    /** Carried by two roles, both are named — an admin removing one would otherwise think the job is done. */
    @Test
    void aPermissionCarriedByTwoHeldRolesNamesBoth() {
        UUID first = role("ROLE_PROV_A", Permissions.USER_READ);
        UUID second = role("ROLE_PROV_B", Permissions.USER_READ);
        UserAccount user = user();
        assign(user.getId(), first);
        assign(user.getId(), second);

        assertThat(explanationOf(user.getId(), Permissions.USER_READ))
                .hasValueSatisfying(explanation -> assertThat(explanation.conferredBy())
                        .containsExactlyInAnyOrder("ROLE_PROV_A", "ROLE_PROV_B"));
    }

    /** A direct grant comes from no role at all, and must not borrow one. */
    @Test
    void aDirectlyGrantedPermissionNamesNoRole() {
        UserAccount user = user();
        ownerJdbc().update("insert into app_user_permission (user_id, permission_id) "
                + "select ?, id from permission where name = ?", user.getId(), Permissions.USER_READ);

        assertThat(explanationOf(user.getId(), Permissions.USER_READ))
                .hasValueSatisfying(explanation -> assertThat(explanation.conferredBy()).isEmpty());
    }

    @Test
    void aPermissionNobodyConfersNamesNoRole() {
        UserAccount user = user();

        assertThat(explanationOf(user.getId(), Permissions.USER_DELETE))
                .hasValueSatisfying(explanation -> assertThat(explanation.conferredBy()).isEmpty());
    }

    /**
     * A role that arrives through a group is removed FROM THE GROUP. Taking it off the user does nothing at
     * all, so naming the role without naming the group still sends an administrator to the wrong screen.
     */
    @Test
    void aPermissionDelegatedByAGroupNamesThatGroup() {
        UUID role = role("ROLE_PROV_GROUPED", Permissions.USER_READ);
        UUID group = group("platform", role);
        UserAccount user = user();
        addToGroup(group, user.getId());

        assertThat(explanationOf(user.getId(), Permissions.USER_READ))
                .hasValueSatisfying(explanation -> {
                    assertThat(explanation.conferredBy()).containsExactly("ROLE_PROV_GROUPED");
                    assertThat(explanation.viaGroups()).containsExactly("platform");
                });
    }

    /** Held directly, the group list stays empty — the user's own assignment is where to remove it. */
    @Test
    void aDirectlyHeldRoleNamesNoGroup() {
        UUID role = role("ROLE_PROV_NOGROUP", Permissions.USER_READ);
        UserAccount user = user();
        assign(user.getId(), role);

        assertThat(explanationOf(user.getId(), Permissions.USER_READ))
                .hasValueSatisfying(explanation -> assertThat(explanation.viaGroups()).isEmpty());
    }

    /** Two groups delegating the same role list both: removing one member row leaves the permission behind. */
    @Test
    void everyGroupDelegatingAConferringRoleIsNamed() {
        UUID role = role("ROLE_PROV_SHARED", Permissions.USER_READ);
        UUID first = group("platform", role);
        UUID second = group("compilers", role);
        UserAccount user = user();
        addToGroup(first, user.getId());
        addToGroup(second, user.getId());

        assertThat(explanationOf(user.getId(), Permissions.USER_READ))
                .hasValueSatisfying(explanation -> assertThat(explanation.viaGroups())
                        .containsExactlyInAnyOrder("platform", "compilers"));
    }

    /**
     * Roles and groups come from ONE walk, so they cannot disagree about the same user. Answering them
     * separately re-resolved the closure per request and — the part that actually bites — computed each from
     * its own held-role set, leaving room for the screen to name a role no group appeared to delegate.
     */
    @Test
    void theRoleAndTheGroupDescribeTheSameGrant() {
        UUID role = role("ROLE_PROV_ONEWALK", Permissions.USER_READ);
        UUID group = group("engineering", role);
        UserAccount user = user();
        addToGroup(group, user.getId());

        assertThat(explanationOf(user.getId(), Permissions.USER_READ))
                .hasValueSatisfying(explanation -> {
                    // A named group with no named role would be an answer about a grant nobody can point at.
                    assertThat(explanation.viaGroups()).isNotEmpty();
                    assertThat(explanation.conferredBy()).isNotEmpty();
                });
    }

    private UUID group(String name, UUID roleId) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into user_group (id, name, org_id) values (?, ?, null)", id, name);
        cleanups.add(() -> ownerJdbc().update("delete from user_group where id = ?", id));
        ownerJdbc().update("insert into group_role (group_id, role_id) values (?, ?)", id, roleId);
        return id;
    }

    private void addToGroup(UUID groupId, UUID userId) {
        ownerJdbc().update("insert into user_group_member (group_id, user_id) values (?, ?)", groupId, userId);
    }

    /**
     * The provenance has to survive the trip to the console, not merely exist in the resolver. A view that
     * dropped it would leave every test above green while the screen still said only "you have it".
     */
    @Test
    void theConsoleDetailCarriesTheConferringRole() {
        UUID role = role("ROLE_PROV_VIEW", Permissions.USER_READ);
        UserAccount user = user();
        assign(user.getId(), role);

        assertThat(userDetail.getUser(user.getId()).effectivePermissions())
                .filteredOn(held -> held.permission().equals(Permissions.USER_READ))
                .singleElement()
                .satisfies(held -> assertThat(held.conferredBy()).containsExactly("ROLE_PROV_VIEW"));
    }

    private UUID role(String name, String... permissions) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into role (id, name, system) values (?, ?, false)", id, name);
        cleanups.add(() -> ownerJdbc().update("delete from role where id = ?", id));
        for (String permission : permissions) {
            ownerJdbc().update("insert into role_permission (role_id, permission_id) "
                    + "select ?, id from permission where name = ?", id, permission);
        }
        return id;
    }

    private void inherit(UUID parent, UUID child) {
        ownerJdbc().update("insert into role_hierarchy (parent_role_id, child_role_id, org_id) "
                + "values (?, ?, null)", parent, child);
    }

    private void assign(UUID userId, UUID roleId) {
        ownerJdbc().update("insert into app_user_role (user_id, role_id) values (?, ?)", userId, roleId);
    }

    private UserAccount user() {
        String username = "prov-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount account = userService.createUser(
                new NewUser(username, username + "@example.com", "Pr", "S3cret!pw", Set.of()));
        cleanups.add(() -> userService.delete(account.getId()));
        return account;
    }
}
