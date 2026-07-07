package com.example.sso.user;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
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
    OrganizationService organizations;

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

    // --- Per-organization resolution: a login resolves the user WITHIN the selected organization (the
    //     tenant), falling back to a global (org-less) user so the platform super-admin still signs in. ---

    @Test
    void resolvesAUserByEmailOrUsernameWithinTheirOrg() {
        UUID orgId = org();
        String username = "scoped-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.com";
        UUID id = userService.createUser(new NewUser(username, email, "Scoped", "S3cret!pw",
                Set.of("ROLE_USER")), orgId).getId();

        assertThat(userService.findByLoginInOrg(email, orgId)).get()
                .extracting(UserAccount::getId).isEqualTo(id);
        assertThat(userService.findByLoginInOrg(username, orgId)).get()
                .extracting(UserAccount::getId).isEqualTo(id);
    }

    @Test
    void doesNotResolveAnOrgUserFromAnotherOrg() {
        UUID home = org();
        UUID other = org();
        String username = "isolated-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.com";
        userService.createUser(new NewUser(username, email, "Isolated", "S3cret!pw", Set.of("ROLE_USER")), home);

        // Resolving from a DIFFERENT org must not find them — the core per-organization isolation property.
        assertThat(userService.findByLoginInOrg(email, other)).isEmpty();
        assertThat(userService.findByLoginInOrg(username, other)).isEmpty();
    }

    @Test
    void resolvesAGlobalUserFromWithinAnyOrg() {
        // A global (orgId == null) user is the platform super-admin. A tenant login (scoped to an org) must
        // still resolve them via the global fallback so they can sign in through a tenant they belong to. The
        // seeded 'admin' is exactly such a global account.
        UUID someOrg = org();

        assertThat(userService.findByLoginInOrg("admin", someOrg)).isPresent();
    }

    @Test
    void apexResolutionSeesOnlyGlobalUsers() {
        UUID orgId = org();
        String username = "tenant-" + UUID.randomUUID().toString().substring(0, 8);
        userService.createUser(new NewUser(username, username + "@example.com", "Tenant", "S3cret!pw",
                Set.of("ROLE_USER")), orgId);

        // orgId == null is the apex/platform resolution: only global (org-less) accounts resolve.
        assertThat(userService.findByLoginInOrg(username, null)).isEmpty();
        assertThat(userService.findByLoginInOrg("admin", null)).isPresent();
    }

    private UUID org() {
        String slug = "o-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug)).id();
    }
}
