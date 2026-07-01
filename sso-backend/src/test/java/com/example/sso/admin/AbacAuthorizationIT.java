package com.example.sso.admin;

import com.example.sso.admin.internal.application.AdminAccessPolicy;
import com.example.sso.admin.internal.application.UserAdminService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.GroupSpec;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserGroupService;
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
    @Autowired
    UserGroupService userGroups;

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
    void otherAdministratorsAreProtected() {
        create("abacactor", Set.of("ROLE_ADMIN", "ROLE_USER"));
        UUID otherAdmin = create("abacpeer", Set.of("ROLE_ADMIN", "ROLE_USER"));
        UUID plain = create("abacplain", Set.of("ROLE_USER"));
        actAs("abacactor");

        // Destructive ops on another admin are blocked; on a normal user they are allowed.
        assertThat(access.canDeleteUser(otherAdmin)).isFalse();
        assertThat(access.canDeleteUser(plain)).isTrue();
        assertThat(access.canSetEnabled(otherAdmin, false)).isFalse();
        assertThat(access.canSetEnabled(plain, false)).isTrue();
        assertThat(access.canResetMfa(otherAdmin)).isFalse();
        assertThat(access.canResetMfa(plain)).isTrue();
        assertThat(access.canManagePermissions(otherAdmin)).isFalse();
        assertThat(access.canManagePermissions(plain)).isTrue();

        // Disabling another admin via update is blocked, but demoting them (role edit) is allowed.
        assertThat(access.canUpdateUser(otherAdmin, false, Set.of("ROLE_ADMIN"))).isFalse();
        assertThat(access.canUpdateUser(otherAdmin, true, Set.of("ROLE_USER"))).isTrue();
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

    @Test
    void groupScopedAdminSeesOnlyManagedUsers() {
        UUID scopedAdmin = create("scopedadmin", Set.of("ROLE_GROUP_ADMIN", "ROLE_USER"));
        UUID managed = create("scopedmember", Set.of("ROLE_USER"));
        UUID other = create("scopedstranger", Set.of("ROLE_USER"));
        UUID groupId = UUID.fromString(userGroups.create(new GroupSpec("ScopeDept", null, null, Set.of(managed))).id());
        userGroups.setManagers(groupId, Set.of(scopedAdmin));

        actAs("scopedadmin");
        assertThat(access.currentIsSuperAdmin()).isFalse();
        assertThat(access.canCreateUser()).isFalse();                 // scoped admins can't mint users
        assertThat(access.canAccessUser(managed)).isTrue();           // member of a managed group
        assertThat(access.canAccessUser(scopedAdmin)).isTrue();       // self
        assertThat(access.canAccessUser(other)).isFalse();            // outside managed groups
        assertThat(access.currentManagedUserIds()).contains(managed).doesNotContain(other);

        // A scoped admin cannot escalate: no permission granting, no privileged-role assignment.
        assertThat(access.canManagePermissions(scopedAdmin)).isFalse();   // can't self-grant permissions
        assertThat(access.canManagePermissions(managed)).isFalse();       // scoped admins can't manage perms
        assertThat(access.canUpdateUser(scopedAdmin, true, Set.of("ROLE_ADMIN", "ROLE_USER"))).isFalse(); // self-promote
        assertThat(access.canUpdateUser(managed, true, Set.of("ROLE_ADMIN"))).isFalse(); // grant admin to a member
        assertThat(access.canUpdateUser(managed, true, Set.of("ROLE_USER"))).isTrue();   // ordinary edit is fine
        assertThat(access.mayAssignRoles(Set.of("ROLE_USER"))).isTrue();                 // group delegation of a plain role
        assertThat(access.mayAssignRoles(Set.of("ROLE_ADMIN"))).isFalse();               // can't delegate admin to a group
        assertThat(access.mayAssignRoles(Set.of("ROLE_GROUP_ADMIN"))).isFalse();

        actAs("admin"); // the seeded super admin is unscoped
        assertThat(access.currentIsSuperAdmin()).isTrue();
        assertThat(access.canCreateUser()).isTrue();
        assertThat(access.canAccessUser(other)).isTrue();
        assertThat(access.canManagePermissions(managed)).isTrue();
        assertThat(access.canUpdateUser(managed, true, Set.of("ROLE_ADMIN"))).isTrue(); // super may grant admin
        assertThat(access.mayAssignRoles(Set.of("ROLE_ADMIN"))).isTrue();               // super may delegate admin

        userGroups.delete(groupId);
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
