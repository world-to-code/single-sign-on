package com.example.sso.user;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;
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
    @Autowired
    OrganizationService organizations;
    @Autowired
    OrgContext orgContext;

    @Test
    void memberInheritsGroupRolePermissionsWithImpliedRead() {
        // Uses a tenant-grantable permission (user:create) whose mutating→read implication is under test;
        // platform perms (e.g. oidc-client:*) are un-grantable outside a super context — a separate concern.
        roleService.create("APP_MANAGER", Set.of(Permissions.USER_CREATE));
        UUID carol = userService.createUser(new NewUser("carol", "carol@example.com", "Carol", "S3cret!pw",
                Set.of("ROLE_USER"))).getId();
        userGroups.create(new GroupSpec("Managers", "app managers", null, Set.of(carol)));
        UUID groupId = UUID.fromString(userGroups.search("Managers", 1).getFirst().id());
        userGroups.setRoles(groupId, Set.of("APP_MANAGER"));

        Set<String> authorities = authoritiesOf("carol");
        assertThat(authorities).contains("APP_MANAGER");          // group-delegated role name
        assertThat(authorities).contains(Permissions.USER_CREATE); // its permission
        assertThat(authorities).contains(Permissions.USER_READ);   // create implies read
    }

    @Test
    void aRoleMayNotBeNamedAfterASystemRole() {
        // A SYSTEM role's name IS emitted as an authority (globally, and for an org's provisioned copies), so
        // a role squatting one of those names would be indistinguishable from the real thing to any check
        // that keys on the name. Minting one is refused outright — in a tenant context too.
        String s = UUID.randomUUID().toString().substring(0, 8);
        UUID orgId = organizations.create(new NewOrganization("esc-" + s, "Esc")).id();

        for (String reserved : List.of(Roles.ADMIN, Roles.ORG_ADMIN, Roles.GROUP_ADMIN, Roles.USER)) {
            assertThatThrownBy(() -> orgContext.callInOrg(orgId,
                    () -> roleService.create(reserved, Set.of(Permissions.USER_READ))))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void aCustomOrgRoleGrantsItsPermissionsButNotItsNameAsAnAuthority() {
        // An org's CUSTOM role contributes only its permissions — never its name — so a tenant can never mint
        // an authority by naming a role after one. (Only the org's provisioned SYSTEM roles emit their name.)
        String s = UUID.randomUUID().toString().substring(0, 8);
        UUID orgId = organizations.create(new NewOrganization("esc-" + s, "Esc")).id();
        String username = "esc-user-" + s;
        UUID user = userService.createUser(new NewUser(username, username + "@example.com", "Esc",
                "S3cret!pw", Set.of("ROLE_USER"))).getId();
        UUID roleId = orgContext.callInOrg(orgId,
                () -> roleService.create("ESCALATOR", Set.of(Permissions.USER_READ))).getId();
        // Managing an org role's membership runs in that org's context (as a drilled-in admin would); the
        // org role is RLS-invisible from an unbound context now that the app runs as a non-superuser.
        orgContext.runInOrg(orgId, () -> roleService.addMember(roleId, user));

        // Authorities are computed bound to the login org (as at real login), so the org role resolves; an
        // unbound computation would not see it now that the app runs as a non-superuser (RLS).
        Set<String> authorities = orgContext.callInOrg(orgId, () -> authoritiesOf(username));
        assertThat(authorities).contains(Permissions.USER_READ);   // the org role's permission is granted
        assertThat(authorities).doesNotContain("ESCALATOR");        // but NOT its name as an authority
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
