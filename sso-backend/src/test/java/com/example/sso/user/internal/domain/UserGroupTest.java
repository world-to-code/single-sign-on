package com.example.sso.user.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link UserGroup} domain behavior: member add/replace, delegated role and manager
 * replacement, rename/describe, and the system-group marker. Pure state assertions, unmodifiable views.
 */
class UserGroupTest {

    private UserGroup newGroup() {
        return new UserGroup("Engineering", "Eng dept", "ext-1");
    }

    @Test
    void newGroupCarriesItsAttributesAndIsNotSystem() {
        UserGroup group = newGroup();

        assertThat(group.getName()).isEqualTo("Engineering");
        assertThat(group.getDescription()).isEqualTo("Eng dept");
        assertThat(group.getExternalId()).isEqualTo("ext-1");
        assertThat(group.isSystem()).isFalse();
        assertThat(group.getMemberUserIds()).isEmpty();
    }

    @Test
    void addMemberThenSetMembersReplacesWholesale() {
        UserGroup group = newGroup();
        UUID first = UUID.randomUUID();
        group.addMember(first);

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        group.setMembers(List.of(a, b));

        assertThat(group.getMemberUserIds()).containsExactlyInAnyOrder(a, b).doesNotContain(first);
    }

    @Test
    void replaceRolesSwapsDelegatedRoles() {
        UserGroup group = newGroup();
        Role editor = new Role("ROLE_EDITOR");

        group.replaceRoles(List.of(editor));

        assertThat(group.getRoles()).containsExactly(editor);
    }

    @Test
    void replaceManagersSwapsManagerSet() {
        UserGroup group = newGroup();
        UUID admin = UUID.randomUUID();

        group.replaceManagers(Set.of(admin));

        assertThat(group.getManagerUserIds()).containsExactly(admin);
    }

    @Test
    void renameAndDescribeMutateInPlace() {
        UserGroup group = newGroup();

        group.rename("Platform");
        group.describe("Platform team");

        assertThat(group.getName()).isEqualTo("Platform");
        assertThat(group.getDescription()).isEqualTo("Platform team");
    }

    @Test
    void markSystemFlipsTheGuardFlag() {
        UserGroup group = new UserGroup(UserGroup.ALL_USERS, null, null);

        group.markSystem();

        assertThat(group.isSystem()).isTrue();
    }

    @Test
    void memberViewIsUnmodifiable() {
        UserGroup group = newGroup();

        assertThatThrownBy(() -> group.getMemberUserIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
