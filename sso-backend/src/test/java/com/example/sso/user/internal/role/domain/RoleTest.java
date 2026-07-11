package com.example.sso.user.internal.role.domain;


import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link Role} domain behavior: rename, the system-role marker, the read-only hydrated
 * permission-name view, and value equality by name. Permission grants themselves are a service concern
 * (explicit {@code role_permission} rows), so the entity only exposes the hydrated names here.
 */
class RoleTest {

    @Test
    void newRoleIsNotSystemAndHasNoPermissions() {
        Role role = new Role("ROLE_EDITOR");

        assertThat(role.getName()).isEqualTo("ROLE_EDITOR");
        assertThat(role.isSystem()).isFalse();
        assertThat(role.getPermissionNames()).isEmpty();
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
    void hydratedPermissionNamesExposeAnUnmodifiableView() {
        Role role = new Role("ROLE_EDITOR");

        role.hydratePermissionNames(List.of("user:read", "role:read"));

        assertThat(role.getPermissionNames()).containsExactlyInAnyOrder("user:read", "role:read");
        assertThatThrownBy(() -> role.getPermissionNames().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rolesAreEqualByNameOnly() {
        Role one = new Role("ROLE_EDITOR");
        Role two = new Role("ROLE_EDITOR");
        two.hydratePermissionNames(List.of("user:read"));

        assertThat(one).isEqualTo(two);
        assertThat(one.hashCode()).isEqualTo(two.hashCode());
        assertThat(new HashSet<>(List.of(one, two))).hasSize(1); // equal-by-name roles collapse
    }
}
