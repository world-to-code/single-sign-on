package com.example.sso.user;

import com.example.sso.customer.CustomerService;
import com.example.sso.customer.NewCustomer;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the identity core end-to-end against a real PostgreSQL (Flyway migrations +
 * Hibernate {@code validate}), through the public {@link UserService} contract only.
 */
class UserServiceIT extends AbstractIntegrationTest {

    @Autowired
    UserService userService;

    @Autowired
    CustomerService customers;

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

    // --- Per-customer resolution (P2): a login resolves the user WITHIN the selected customer (고객사),
    //     falling back to a global (customer-less) user so the platform super-admin still signs in. ---

    @Test
    void resolvesAUserByEmailOrUsernameWithinTheirCustomer() {
        UUID customerId = customer();
        String username = "scoped-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.com";
        UUID id = userService.createUser(new NewUser(username, email, "Scoped", "S3cret!pw",
                Set.of("ROLE_USER")), customerId).getId();

        assertThat(userService.findByLoginInCustomer(email, customerId)).get()
                .extracting(UserAccount::getId).isEqualTo(id);
        assertThat(userService.findByLoginInCustomer(username, customerId)).get()
                .extracting(UserAccount::getId).isEqualTo(id);
    }

    @Test
    void doesNotResolveACustomerUserFromAnotherCustomer() {
        UUID home = customer();
        UUID other = customer();
        String username = "isolated-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.com";
        userService.createUser(new NewUser(username, email, "Isolated", "S3cret!pw", Set.of("ROLE_USER")), home);

        // Resolving from a DIFFERENT customer must not find them — the core per-customer isolation property.
        assertThat(userService.findByLoginInCustomer(email, other)).isEmpty();
        assertThat(userService.findByLoginInCustomer(username, other)).isEmpty();
    }

    @Test
    void resolvesAGlobalUserFromWithinAnyCustomer() {
        // A customer-less (customerId == null) user is the platform super-admin. A tenant login (scoped to a
        // customer) must still resolve them via the global fallback so they can sign in through a tenant they
        // belong to. The seeded 'admin' is exactly such a global account.
        UUID someCustomer = customer();

        assertThat(userService.findByLoginInCustomer("admin", someCustomer)).isPresent();
    }

    @Test
    void apexResolutionSeesOnlyGlobalUsers() {
        UUID customerId = customer();
        String username = "tenant-" + UUID.randomUUID().toString().substring(0, 8);
        userService.createUser(new NewUser(username, username + "@example.com", "Tenant", "S3cret!pw",
                Set.of("ROLE_USER")), customerId);

        // customerId == null is the apex/platform resolution: only global (customer-less) accounts resolve.
        assertThat(userService.findByLoginInCustomer(username, null)).isEmpty();
        assertThat(userService.findByLoginInCustomer("admin", null)).isPresent();
    }

    private UUID customer() {
        String slug = "c-" + UUID.randomUUID().toString().substring(0, 8);
        return customers.create(new NewCustomer(slug, slug)).id();
    }
}
