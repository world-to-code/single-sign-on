package com.example.sso.user.internal.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link UserGroup} domain behavior: attribute construction, rename/describe, and the
 * system-group marker. Membership and delegated roles are managed as explicit join rows in the service
 * layer, not on the aggregate, so they are covered by the service/integration tests.
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
}
