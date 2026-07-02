package com.example.sso.user;

import com.example.sso.shared.error.ConflictException;
import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the identity core end-to-end against a real PostgreSQL (Flyway migrations +
 * Hibernate {@code validate}), through the public {@link UserService} contract only.
 */
class UserServiceIT extends AbstractIntegrationTest {

    @Autowired
    UserService userService;

    @Test
    void createsAndLoadsUserWithRole() {
        userService.createUser(new NewUser("alice", "alice@example.com", "Alice", "S3cret!pw", Set.of("ROLE_USER")));

        UserAccount loaded = userService.findByUsername("alice").orElseThrow();
        assertThat(loaded.getId()).isNotNull();
        assertThat(loaded.getEmail()).isEqualTo("alice@example.com");
        assertThat(userService.verifyPassword("alice", "S3cret!pw")).isTrue();  // stored as a verifiable hash
        assertThat(userService.verifyPassword("alice", "wrong-pw")).isFalse();
        assertThat(loaded.getRoles()).extracting(RoleRef::getName).contains("ROLE_USER");
    }

    @Test
    void rejectsDuplicateUsername() {
        userService.createUser(new NewUser("bob", "bob@example.com", "Bob", "pw-one-2!", Set.of("ROLE_USER")));
        assertThatThrownBy(() ->
                userService.createUser(new NewUser("bob", "bob2@example.com", "Bob2", "pw-two-2!",
                        Set.of("ROLE_USER"))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void seedsDefaultAdminOnStartup() {
        assertThat(userService.findByUsername("admin")).isPresent();
    }
}
