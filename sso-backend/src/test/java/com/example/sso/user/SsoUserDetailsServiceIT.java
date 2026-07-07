package com.example.sso.user;

import com.example.sso.customer.CustomerService;
import com.example.sso.customer.NewCustomer;
import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The auth-bypass guard for per-customer identity: Spring Security's password provider calls
 * {@link UserDetailsService#loadUserByUsername} with only a username, so the resolver must honor the
 * customer (고객사) the login orchestrator bound via {@link LoginResolutionScope}. Proven against a real DB:
 * a username resolves WITHIN the bound customer, a foreign-tenant user is invisible, and a global
 * (customer-less) super-admin still resolves via the fallback so they sign in through any tenant.
 */
class SsoUserDetailsServiceIT extends AbstractIntegrationTest {

    @Autowired
    UserDetailsService userDetailsService;
    @Autowired
    UserService userService;
    @Autowired
    CustomerService customers;
    @Autowired
    LoginResolutionScope loginScope;

    @Test
    void scopedResolutionFindsTheCustomersOwnUser() {
        UUID customerId = customer();
        String username = createUserIn(customerId);

        UserDetails resolved = loginScope.within(customerId, () -> userDetailsService.loadUserByUsername(username));

        assertThat(resolved.getUsername()).isEqualTo(username);
    }

    @Test
    void scopedResolutionDoesNotSeeAnotherCustomersUser() {
        UUID home = customer();
        UUID other = customer();
        String username = createUserIn(home);

        // Bound to a DIFFERENT customer, the password provider must NOT resolve this user — else a username
        // shared across tenants could authenticate against the wrong account (auth bypass).
        assertThatThrownBy(() -> loginScope.within(other, () -> userDetailsService.loadUserByUsername(username)))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void scopedResolutionFallsBackToTheGlobalSuperAdmin() {
        UUID customerId = customer();

        // The seeded 'admin' is a global (customer-less) account; a tenant-scoped login still resolves it.
        UserDetails resolved = loginScope.within(customerId, () -> userDetailsService.loadUserByUsername("admin"));

        assertThat(resolved.getUsername()).isEqualTo("admin");
    }

    @Test
    void unscopedResolutionUsesTheGlobalLookup() {
        UUID customerId = customer();
        String username = createUserIn(customerId);

        // No scope bound (a non-login caller) → the legacy global lookup, so behavior is preserved.
        assertThat(userDetailsService.loadUserByUsername(username).getUsername()).isEqualTo(username);
    }

    @Test
    void apexResolutionSeesOnlyGlobalUsers() {
        UUID customerId = customer();
        String username = createUserIn(customerId);

        // Bound to the apex/platform scope (null customer) — a tenant user is invisible, the super-admin resolves.
        assertThatThrownBy(() -> loginScope.within(null, () -> userDetailsService.loadUserByUsername(username)))
                .isInstanceOf(UsernameNotFoundException.class);
        assertThat(loginScope.within(null, () -> userDetailsService.loadUserByUsername("admin")).getUsername())
                .isEqualTo("admin");
    }

    private UUID customer() {
        String slug = "c-" + UUID.randomUUID().toString().substring(0, 8);
        return customers.create(new NewCustomer(slug, slug)).id();
    }

    private String createUserIn(UUID customerId) {
        String username = "u-" + UUID.randomUUID().toString().substring(0, 8);
        userService.createUser(new NewUser(username, username + "@example.com", "U", "S3cret!pw",
                Set.of("ROLE_USER")), customerId);
        return username;
    }
}
