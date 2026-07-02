package com.example.sso.user.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link Role} domain behavior: rename, the system-role marker, wholesale permission
 * replacement, the names projection, and value equality by name. Pure state assertions.
 */
class RoleTest {

    @Test
    void newRoleIsNotSystemAndHasNoPermissions() {
        Role role = new Role("ROLE_EDITOR");

        assertThat(role.getName()).isEqualTo("ROLE_EDITOR");
        assertThat(role.isSystem()).isFalse();
        assertThat(role.getPermissions()).isEmpty();
    }

    @Test
    void renameChangesTheName() {
        Role role = new Role("ROLE_EDITOR");

        role.rename("ROLE_WRITER");

        assertThat(role.getName()).isEqualTo("ROLE_WRITER");
    }

    @Test
    void markSystemFlipsTheGuardFlag() {
        Role role = new Role("ROLE_ADMIN");

        role.markSystem();

        assertThat(role.isSystem()).isTrue();
    }

    @Test
    void replacePermissionsSwapsTheSetWholesaleAndProjectsNames() {
        Role role = new Role("ROLE_EDITOR");
        role.addPermission(new Permission("user:create"));

        role.replacePermissions(List.of(new Permission("user:read"), new Permission("role:read")));

        assertThat(role.getPermissionNames()).containsExactlyInAnyOrder("user:read", "role:read");
    }

    @Test
    void permissionViewIsUnmodifiable() {
        Role role = new Role("ROLE_EDITOR");

        assertThatThrownBy(() -> role.getPermissions().add(new Permission("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rolesAreEqualByNameOnly() {
        Role one = new Role("ROLE_EDITOR");
        Role two = new Role("ROLE_EDITOR");
        two.addPermission(new Permission("user:read"));

        assertThat(one).isEqualTo(two);
        assertThat(one.hashCode()).isEqualTo(two.hashCode());
        assertThat(new HashSet<>(List.of(one, two))).hasSize(1); // equal-by-name roles collapse

    }
}
