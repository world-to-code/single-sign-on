package com.example.sso.user;

import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the identity core end-to-end against a real PostgreSQL (Flyway migrations +
 * Hibernate {@code validate}).
 */
class UserServiceIT extends AbstractIntegrationTest {

    @Autowired
    UserService userService;

    @Autowired
    AppUserRepository users;

    @Test
    void createsAndLoadsUserWithRole() {
        userService.createUser("alice", "alice@example.com", "Alice", "S3cret!pw", Set.of("ROLE_USER"));

        AppUser loaded = users.findByUsername("alice").orElseThrow();
        assertThat(loaded.getId()).isNotNull();
        assertThat(loaded.getEmail()).isEqualTo("alice@example.com");
        assertThat(loaded.getPasswordHash()).isNotBlank();
        assertThat(loaded.getPasswordHash()).doesNotContain("S3cret!pw"); // stored as a hash
        assertThat(loaded.getRoles()).extracting(Role::getName).contains("ROLE_USER");
    }

    @Test
    void rejectsDuplicateUsername() {
        userService.createUser("bob", "bob@example.com", "Bob", "pw-one-2!", Set.of("ROLE_USER"));
        assertThatThrownBy(() ->
                userService.createUser("bob", "bob2@example.com", "Bob2", "pw-two-2!", Set.of("ROLE_USER")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void seedsDefaultAdminOnStartup() {
        assertThat(users.findByUsername("admin")).isPresent();
    }
}
