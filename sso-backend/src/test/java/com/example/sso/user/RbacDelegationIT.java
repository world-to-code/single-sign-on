package com.example.sso.user;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies RBAC/PBAC authority resolution end-to-end: a role delegated to a group is inherited by its
 * members (role name + permissions), a mutating permission implies read, and system roles are protected.
 */
class RbacDelegationIT extends AbstractIntegrationTest {

    @Autowired
    UserService userService;
    @Autowired
    RoleService roleService;
    @Autowired
    UserGroupService userGroups;
    @Autowired
    UserDetailsService userDetailsService;

    @Test
    void memberInheritsGroupRolePermissionsWithImpliedRead() {
        roleService.create("APP_MANAGER", Set.of(Permissions.CLIENT_CREATE));
        UUID carol = userService.createUser(new NewUser("carol", "carol@example.com", "Carol", "S3cret!pw",
                Set.of("ROLE_USER"))).getId();
        userGroups.create(new GroupSpec("Managers", "app managers", null, Set.of(carol)));
        UUID groupId = UUID.fromString(userGroups.search("Managers", 1).getFirst().id());
        userGroups.setRoles(groupId, Set.of("APP_MANAGER"));

        Set<String> authorities = authoritiesOf("carol");
        assertThat(authorities).contains("APP_MANAGER");          // group-delegated role name
        assertThat(authorities).contains(Permissions.CLIENT_CREATE); // its permission
        assertThat(authorities).contains(Permissions.CLIENT_READ);   // create implies read
    }

    @Test
    void groupDelegatedAdminRoleGrantsAdminAuthority() {
        UUID dave = userService.createUser(new NewUser("dave", "dave@example.com", "Dave", "S3cret!pw",
                Set.of("ROLE_USER"))).getId();
        userGroups.create(new GroupSpec("Admins", "delegated admins", null, Set.of(dave)));
        UUID groupId = UUID.fromString(userGroups.search("Admins", 1).getFirst().id());
        userGroups.setRoles(groupId, Set.of("ROLE_ADMIN"));

        assertThat(authoritiesOf("dave")).contains("ROLE_ADMIN");
    }

    @Test
    void systemRolesCannotBeRenamedOrDeleted() {
        UUID adminId = roleService.findByName("ROLE_ADMIN").orElseThrow().getId();
        UUID userRoleId = roleService.findByName("ROLE_USER").orElseThrow().getId();

        assertThatThrownBy(() -> roleService.deleteRole(adminId)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> roleService.updateRole(userRoleId, "RENAMED", Set.of()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> roleService.updateRole(adminId, "ROLE_ADMIN", Set.of()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void rejectsRoleNamesCollidingWithReservedAuthorities() {
        // A role name is emitted verbatim as an authority, so reserved authorities must not be mintable.
        assertThatThrownBy(() -> roleService.create("MFA_COMPLETE", Set.of()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> roleService.create("FACTOR_TOTP", Set.of()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> roleService.create(Permissions.USER_READ, Set.of()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> roleService.create("ROLE_SCIM", Set.of()))
                .isInstanceOf(BadRequestException.class);
        // A normal role name is accepted.
        assertThat(roleService.create("ROLE_SUPPORT", Set.of(Permissions.USER_READ)).getName())
                .isEqualTo("ROLE_SUPPORT");
    }

    @Test
    void userRoleAssignmentRejectsUnknownRoleNames() {
        // A user-management call must not mint a role whose name is a reserved authority (authority injection).
        UUID erin = userService.createUser(new NewUser("erin", "erin@example.com", "Erin", "S3cret!pw",
                Set.of("ROLE_USER"))).getId();

        assertThatThrownBy(() -> userService.updateUser(erin,
                new UserUpdate("Erin", "erin@example.com", true, Set.of("MFA_COMPLETE"))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> userService.createUser(new NewUser("mallory", "mallory@example.com", "M",
                "S3cret!pw", Set.of("key:rotate"))))
                .isInstanceOf(BadRequestException.class);
    }

    private Set<String> authoritiesOf(String username) {
        UserDetails details = userDetailsService.loadUserByUsername(username);
        return details.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
