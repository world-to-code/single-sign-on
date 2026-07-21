package com.example.sso.user;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.group.UserGroupService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The single-member and batch membership operations added for programmatic membership (auto-mapping): same-org
 * and system-group guards, unknown-user no-op, and idempotency — the branches only exercised indirectly by the
 * mapping evaluator, pinned here directly.
 */
class UserGroupMemberOpsIT extends AbstractIntegrationTest {

    @Autowired UserGroupService groups;
    @Autowired UserService users;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();
    private final List<UUID> createdOrgs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        orgContext.runAsPlatform(() -> {
            createdUsers.forEach(users::delete);
            createdGroups.forEach(id -> {
                ownerJdbc().update("update user_group set system = false where id = ?", id); // un-flag so delete is allowed
                groups.delete(id);
            });
            createdOrgs.forEach(id -> ownerJdbc().update("delete from organization where id = ?", id));
        });
        createdUsers.clear();
        createdGroups.clear();
        createdOrgs.clear();
    }

    @Test
    void addMemberEnforcesSameOrgRejectsSystemGroupsAndIsIdempotent() {
        UUID org = newOrg("mem-a");
        UUID other = newOrg("mem-b");
        UUID group = orgContext.callInOrg(org, () -> group());
        UUID member = orgContext.callInOrg(org, () -> user(org));
        UUID foreign = orgContext.callInOrg(other, () -> user(other));

        orgContext.runInOrg(org, () -> {
            groups.addMember(group, member);
            groups.addMember(group, member);                               // idempotent — still one row
            assertThat(memberCount(group)).isEqualTo(1);

            groups.addMember(group, UUID.randomUUID());                    // unknown user — dropped, no-op
            assertThat(memberCount(group)).isEqualTo(1);

            assertThatThrownBy(() -> groups.addMember(group, foreign))     // a different org's user
                    .isInstanceOf(BadRequestException.class);
        });

        UUID system = markSystem(group);
        orgContext.runInOrg(org, () -> {
            assertThatThrownBy(() -> groups.addMember(system, member)).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> groups.removeMember(system, member)).isInstanceOf(ConflictException.class);
        });
    }

    @Test
    void removeMemberIsIdempotent() {
        UUID org = newOrg("mem-rm");
        UUID group = orgContext.callInOrg(org, () -> group());
        UUID member = orgContext.callInOrg(org, () -> user(org));

        orgContext.runInOrg(org, () -> {
            groups.removeMember(group, member);                            // not a member yet — no-op
            groups.addMember(group, member);
            groups.removeMember(group, member);
            groups.removeMember(group, member);                            // idempotent
            assertThat(memberCount(group)).isZero();
        });
    }

    @Test
    void addMembersAddsTheValidSubsetAndRejectsACrossOrgUser() {
        UUID org = newOrg("mem-batch");
        UUID other = newOrg("mem-batch-b");
        UUID group = orgContext.callInOrg(org, () -> group());
        UUID a = orgContext.callInOrg(org, () -> user(org));
        UUID b = orgContext.callInOrg(org, () -> user(org));
        UUID foreign = orgContext.callInOrg(other, () -> user(other));

        orgContext.runInOrg(org, () -> {
            groups.addMembers(group, Set.of(a, b, UUID.randomUUID()));     // unknown dropped, the two added
            assertThat(memberCount(group)).isEqualTo(2);

            assertThatThrownBy(() -> groups.addMembers(group, Set.of(a, foreign)))
                    .isInstanceOf(BadRequestException.class);              // a foreign-org user aborts the batch
        });
    }

    /**
     * The roles a set of groups delegates, read in one query.
     *
     * <p>A real database is the only thing that can prove this one: it is an interface projection, so the
     * query's column aliases have to match the accessor names, and a mock of the service asserts nothing about
     * either. The import path refuses a group whose delegated roles the actor may not assign, so a query that
     * silently returned nothing would open that ceiling rather than close it.
     */
    @Test
    void delegatedRoleNamesAnswersForEveryGroupInOneCall() {
        UUID org = newOrg("mem-roles");
        UUID withRoles = orgContext.callInOrg(org, () -> group());
        UUID alsoWithRoles = orgContext.callInOrg(org, () -> group());
        UUID withNone = orgContext.callInOrg(org, () -> group());

        orgContext.runInOrg(org, () -> {
            groups.setRoles(withRoles, Set.of("ROLE_USER"));
            groups.setRoles(alsoWithRoles, Set.of("ROLE_USER", "ROLE_GROUP_ADMIN"));

            Map<UUID, Set<String>> delegated =
                    groups.delegatedRoleNames(List.of(withRoles, alsoWithRoles, withNone));

            assertThat(delegated.get(withRoles)).containsExactly("ROLE_USER");
            assertThat(delegated.get(alsoWithRoles)).containsExactlyInAnyOrder("ROLE_USER", "ROLE_GROUP_ADMIN");
            // Absent, not an empty set — the caller reads null as "delegates nothing, so no ceiling applies".
            assertThat(delegated).doesNotContainKey(withNone);
        });
    }

    @Test
    void delegatedRoleNamesIsANoOpForAnEmptySet() {
        assertThat(groups.delegatedRoleNames(List.of())).isEmpty();
    }

    @Test
    void addMembersIsANoOpForAnEmptySet() {
        UUID org = newOrg("mem-empty");
        UUID group = orgContext.callInOrg(org, () -> group());
        assertThatCode(() -> orgContext.runInOrg(org, () -> groups.addMembers(group, Set.of())))
                .doesNotThrowAnyException();
    }

    private long memberCount(UUID groupId) {
        return groups.members(groupId, 0, 100).total();
    }

    private UUID markSystem(UUID groupId) {
        orgContext.runAsPlatform(() -> ownerJdbc().update("update user_group set system = true where id = ?", groupId));
        return groupId;
    }

    private UUID group() {
        UUID id = UUID.fromString(groups.create(new GroupSpec("mg-" + suffix(), null, null, Set.of())).id());
        createdGroups.add(id);
        return id;
    }

    private UUID user(UUID org) {
        String s = suffix();
        UUID id = users.createUser(new NewUser("mu-" + s, "mu-" + s + "@example.com", "M " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), org).getId();
        createdUsers.add(id);
        return id;
    }

    private UUID newOrg(String prefix) {
        UUID id = orgContext.callAsPlatform(
                () -> organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id());
        createdOrgs.add(id);
        return id;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
