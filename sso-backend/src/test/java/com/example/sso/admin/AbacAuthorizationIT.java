package com.example.sso.admin;

import com.example.sso.admin.internal.application.AdminAccessPolicy;
import com.example.sso.admin.internal.application.UserAdminService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Instance-level (ABAC) authorization: the actor-relative self-protection rules ({@link AdminAccessPolicy},
 * used from {@code @PreAuthorize}) and the actor-independent last-administrator invariant
 * ({@link UserAdminService}). Each test cleans up the users it creates so the global admin count is not
 * polluted for sibling tests (the Testcontainer DB is shared without per-test rollback).
 */
class AbacAuthorizationIT extends AbstractIntegrationTest {

    @Autowired
    AdminAccessPolicy access;
    @Autowired
    UserAdminService userAdmin;
    @Autowired
    UserService userService;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        created.forEach(id -> {
            try {
                userService.delete(id);
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
        });
        created.clear();
    }

    @Test
    void selfProtectionRules() {
        UUID actor = create("abacactor", Set.of("ROLE_ADMIN", "ROLE_USER"));
        UUID other = create("abacother", Set.of("ROLE_USER"));
        actAs("abacactor");

        assertThat(access.canDeleteUser(actor)).isFalse();                          // cannot delete self
        assertThat(access.canDeleteUser(other)).isTrue();
        assertThat(access.canSetEnabled(actor, false)).isFalse();                   // cannot disable self
        assertThat(access.canSetEnabled(actor, true)).isTrue();                     // may re-enable self
        assertThat(access.canSetEnabled(other, false)).isTrue();
        assertThat(access.canUpdateUser(actor, true, Set.of("ROLE_USER"))).isFalse();               // drops own admin
        assertThat(access.canUpdateUser(actor, true, Set.of("ROLE_ADMIN", "ROLE_USER"))).isTrue();  // keeps own admin
        assertThat(access.canUpdateUser(actor, false, Set.of("ROLE_ADMIN"))).isFalse();             // self-disable
        assertThat(access.canUpdateUser(other, false, Set.of())).isTrue();
    }

    @Test
    void lastAdministratorIsProtected() {
        UUID seededAdmin = userService.findByUsername("admin").orElseThrow().getId();

        // The seeded admin is the only enabled direct ROLE_ADMIN: removing it is rejected (before any write).
        assertThatThrownBy(() -> userAdmin.deleteUser(seededAdmin)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> userAdmin.setEnabled(seededAdmin, false)).isInstanceOf(ConflictException.class);

        // With a second enabled admin present, removing that second admin is allowed.
        UUID second = create("abacadmin2", Set.of("ROLE_ADMIN", "ROLE_USER"));
        userAdmin.deleteUser(second);
        created.remove(second);
    }

    private UUID create(String username, Set<String> roles) {
        UUID id = userService.createUser(new NewUser(username, username + "@example.com", username,
                "S3cret!pw9", roles)).getId();
        created.add(id);
        return id;
    }

    private void actAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }
}
