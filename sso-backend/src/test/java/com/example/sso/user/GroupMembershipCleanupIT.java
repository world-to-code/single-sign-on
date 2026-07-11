package com.example.sso.user;

import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;
import com.example.sso.user.group.GroupMembersPage;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.group.UserGroupService;

import com.example.sso.support.AbstractIntegrationTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting a user must remove their group memberships. {@code user_group_member} has no FK to
 * {@code app_user}, so a deleted user's rows survive: the group's member COUNT (which counts join rows) then
 * disagrees with its member LIST (which joins {@code app_user}), and the group page shows "5 members" while
 * listing four. Group-based app assignments and role delegation also key on those rows.
 */
class GroupMembershipCleanupIT extends AbstractIntegrationTest {

    @Autowired
    UserService userService;
    @Autowired
    UserGroupService userGroups;

    private UUID groupId;
    private UUID secondGroupId;

    @AfterEach
    void tearDown() {
        if (groupId != null) {
            userGroups.delete(groupId);
        }
        if (secondGroupId != null) {
            userGroups.delete(secondGroupId);
        }
    }

    private UUID user(String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return userService.createUser(new NewUser(username, username + "@example.com", "U",
                "S3cret!pw", Set.of("ROLE_USER"))).getId();
    }

    @Test
    void deletingAUserRemovesThemFromEveryGroup() {
        UUID staying = user("stays");
        UUID leaving = user("leaves");
        groupId = UUID.fromString(userGroups.create(
                new GroupSpec("Cleanup-" + UUID.randomUUID().toString().substring(0, 8),
                        "membership cleanup", null, Set.of(staying, leaving))).id());
        assertThat(userGroups.members(groupId, 0, 20).total()).isEqualTo(2);

        userService.delete(leaving);

        GroupMembersPage members = userGroups.members(groupId, 0, 20);
        // The count and the listing must agree — a surviving join row inflates the count only.
        assertThat(members.total()).isEqualTo(1);
        assertThat(members.items()).hasSize(1);
        assertThat(members.items().getFirst().id()).isEqualTo(staying.toString());
    }

    @Test
    void aDeletedUsersMembershipNoLongerCountsTowardsAnyGroup() {
        UUID member = user("solo");
        groupId = UUID.fromString(userGroups.create(
                new GroupSpec("Solo-" + UUID.randomUUID().toString().substring(0, 8),
                        "single member", null, Set.of(member))).id());

        userService.delete(member);

        assertThat(userGroups.members(groupId, 0, 20).total()).isZero();
        assertThat(userGroups.groupIdsOf(member)).isEmpty();
    }

    @Test
    void deletingAUserRemovesThemFromEVERYGroupTheyBelongTo() {
        // deleteByUserId is unscoped by group; a delete that only reached one membership would leave the other
        // group's count inflated. One group per test would never notice.
        UUID member = user("multi");
        groupId = UUID.fromString(userGroups.create(
                new GroupSpec("First-" + UUID.randomUUID().toString().substring(0, 8),
                        "first", null, Set.of(member))).id());
        secondGroupId = UUID.fromString(userGroups.create(
                new GroupSpec("Second-" + UUID.randomUUID().toString().substring(0, 8),
                        "second", null, Set.of(member))).id());

        userService.delete(member);

        assertThat(userGroups.members(groupId, 0, 20).total()).isZero();
        assertThat(userGroups.members(secondGroupId, 0, 20).total()).isZero();
        assertThat(userGroups.groupIdsOf(member)).isEmpty();
    }
}
