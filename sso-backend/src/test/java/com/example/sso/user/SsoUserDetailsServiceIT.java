package com.example.sso.user;

import com.example.sso.user.account.LoginResolutionScope;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
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
 * The auth-bypass guard for per-organization identity: Spring Security's password provider calls
 * {@link UserDetailsService#loadUserByUsername} with only a username, so the resolver must honor the
 * organization the login orchestrator bound via {@link LoginResolutionScope}. Proven against a real DB:
 * a username resolves WITHIN the bound org, a foreign-tenant user is invisible, and a global (org-less)
 * super-admin still resolves via the fallback so they sign in through any tenant.
 */
class SsoUserDetailsServiceIT extends AbstractIntegrationTest {

    @Autowired
    UserDetailsService userDetailsService;
    @Autowired
    UserService userService;
    @Autowired
    OrganizationService organizations;
    @Autowired
    LoginResolutionScope loginScope;

    @Test
    void scopedResolutionFindsTheOrgsOwnUser() {
        UUID orgId = org();
        String username = createUserIn(orgId);

        UserDetails resolved = loginScope.within(orgId, () -> userDetailsService.loadUserByUsername(username));

        assertThat(resolved.getUsername()).isEqualTo(username);
    }

    @Test
    void scopedResolutionDoesNotSeeAnotherOrgsUser() {
        UUID home = org();
        UUID other = org();
        String username = createUserIn(home);

        // Bound to a DIFFERENT org, the password provider must NOT resolve this user — else a username shared
        // across tenants could authenticate against the wrong account (auth bypass).
        assertThatThrownBy(() -> loginScope.within(other, () -> userDetailsService.loadUserByUsername(username)))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void scopedResolutionFallsBackToTheGlobalSuperAdmin() {
        UUID orgId = org();

        // The seeded 'admin' is a global (org-less) account; a tenant-scoped login still resolves it.
        UserDetails resolved = loginScope.within(orgId, () -> userDetailsService.loadUserByUsername("admin"));

        assertThat(resolved.getUsername()).isEqualTo("admin");
    }

    @Test
    void unscopedResolutionResolvesOnlyGlobalUsers() {
        // No scope bound (a non-login caller): only a GLOBAL account resolves (the seeded 'admin'). An
        // org-scoped user is invisible — fail closed rather than risk a non-unique match on the now per-org
        // username column.
        assertThat(userDetailsService.loadUserByUsername("admin").getUsername()).isEqualTo("admin");

        String orgUser = createUserIn(org());
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(orgUser))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void apexResolutionSeesOnlyGlobalUsers() {
        UUID orgId = org();
        String username = createUserIn(orgId);

        // Bound to the apex/platform scope (null org) — a tenant user is invisible, the super-admin resolves.
        assertThatThrownBy(() -> loginScope.within(null, () -> userDetailsService.loadUserByUsername(username)))
                .isInstanceOf(UsernameNotFoundException.class);
        assertThat(loginScope.within(null, () -> userDetailsService.loadUserByUsername("admin")).getUsername())
                .isEqualTo("admin");
    }

    private UUID org() {
        String slug = "o-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug)).id();
    }

    private String createUserIn(UUID orgId) {
        String username = "u-" + UUID.randomUUID().toString().substring(0, 8);
        userService.createUser(new NewUser(username, username + "@example.com", "U", "S3cret!pw",
                Set.of("ROLE_USER")), orgId);
        return username;
    }
}
