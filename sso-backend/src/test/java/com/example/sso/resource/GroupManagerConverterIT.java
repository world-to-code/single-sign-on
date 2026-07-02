package com.example.sso.resource;

import com.example.sso.resource.internal.application.GroupManagerConverter;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.GroupSpec;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5b Step 1: the one-time converter that turns legacy group-managers into resource ADMIN grants.
 * A manager of group G must, after conversion, hold G in their resource scope (via a bridging resource
 * that has G as a GROUP member) — the faithful replacement for the {@code user_group_manager} mechanism.
 * Conversion is idempotent (safe to run on every boot).
 */
class GroupManagerConverterIT extends AbstractIntegrationTest {

    @Autowired
    GroupManagerConverter converter;
    @Autowired
    UserGroupService userGroups;
    @Autowired
    UserService userService;
    @Autowired
    GroupAuthorization groupAuth;
    @Autowired
    UserAuthorization userAuth;
    @Autowired
    ResourceRepository resources;
    @Autowired
    ResourceTypeRepository types;
    @Autowired
    JdbcTemplate jdbc;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        resources.deleteAll();
        types.findByNameFetchingKinds(GroupManagerConverter.TYPE_NAME).ifPresent(types::delete);
        createdGroups.forEach(userGroups::delete);
        createdGroups.clear();
        createdUsers.forEach(userService::delete);
        createdUsers.clear();
    }

    @Test
    void aLegacyManagerGainsResourceScopeOverTheirGroupsMembers() {
        UUID manager = user("conv-manager");
        UUID member = user("conv-member");
        UUID group = group("Conv-Group", member);
        jdbc.update("INSERT INTO user_group_manager(group_id, user_id) VALUES (?, ?)", group, manager);

        // Before conversion: no resource scope at all.
        assertThat(groupAuth.scopedGroupIds(manager)).doesNotContain(group);

        converter.convert();

        // After: the group (and hence its members) is inside the manager's resource subtree.
        assertThat(groupAuth.scopedGroupIds(manager)).contains(group);
        assertThat(userAuth.scopedUserIds(manager)).contains(member);
    }

    @Test
    void conversionIsIdempotent() {
        UUID manager = user("conv-manager2");
        UUID group = group("Conv-Group2", manager);
        jdbc.update("INSERT INTO user_group_manager(group_id, user_id) VALUES (?, ?)", group, manager);

        converter.convert();
        converter.convert();

        long bridging = resources.findAllForAdminView().stream()
                .filter(r -> r.getType().getName().equals(GroupManagerConverter.TYPE_NAME))
                .filter(r -> r.getMembers().stream()
                        .anyMatch(m -> m.memberType() == MemberType.GROUP && m.memberId().equals(group.toString())))
                .count();
        assertThat(bridging).isEqualTo(1);
    }

    private UUID user(String username) {
        UUID id = userService.createUser(new NewUser(username, username + "@example.com", username,
                "S3cret!pw9", Set.of("ROLE_USER"))).getId();
        createdUsers.add(id);
        return id;
    }

    private UUID group(String name, UUID memberId) {
        UUID id = UUID.fromString(userGroups.create(new GroupSpec(name, null, null, Set.of(memberId))).id());
        createdGroups.add(id);
        return id;
    }
}
