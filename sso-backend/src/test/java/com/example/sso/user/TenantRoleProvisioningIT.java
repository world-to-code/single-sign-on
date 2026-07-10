package com.example.sso.user;

import com.example.sso.bootstrap.internal.TenantRoleProvisioner;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.portal.AppAssignmentView;
import com.example.sso.portal.AppType;
import com.example.sso.portal.ApplicationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creating an organization provisions that tenant's OWN baseline system roles (via
 * OrganizationCreatedEvent → TenantRoleProvisioner, SYNCHRONOUSLY in the creating transaction): the
 * tenant's role page shows real, org-owned ROLE_USER / ROLE_GROUP_ADMIN / ROLE_ORG_ADMIN; a user created
 * in the org is assigned the ORG's roles by name (not the global fallbacks); the org's ROLE_ORG_ADMIN
 * carries only tenant-grantable permissions, is granted admin-console entry, and its well-known name is
 * emitted as an authority. Startup backfill migrates pre-existing members off the global baseline roles.
 */
class TenantRoleProvisioningIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;
    @Autowired
    RoleService roleService;
    @Autowired
    RbacService rbacService;
    @Autowired
    UserService userService;
    @Autowired
    OrgContext orgContext;
    @Autowired
    ApplicationService applications;
    @Autowired
    RegisteredClientRepository clients;
    @Autowired
    UserDetailsService userDetailsService;
    @Autowired
    LoginResolutionScope loginScope;
    @Autowired
    TenantRoleProvisioner provisioner;

    private UUID org() {
        String slug = "role-it-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug)).id();
    }

    private List<RoleRef> orgRoles(UUID orgId) {
        return orgContext.callInOrg(orgId, () -> roleService.findAll()).stream()
                .filter(role -> orgId.equals(role.getOrgId())).toList();
    }

    private RoleRef orgRole(UUID orgId, String name) {
        return orgRoles(orgId).stream().filter(role -> name.equals(role.getName())).findFirst().orElseThrow();
    }

    @Test
    void creatingAnOrganizationProvisionsItsOwnBaselineSystemRoles() {
        UUID orgId = org();

        List<RoleRef> provisioned = orgRoles(orgId);
        assertThat(provisioned).extracting(RoleRef::getName)
                .containsExactlyInAnyOrder(Roles.USER, Roles.GROUP_ADMIN, Roles.ORG_ADMIN);
        assertThat(provisioned).allMatch(RoleRef::isSystem);

        // The org's ROLE_ORG_ADMIN owns the tenant-grantable catalog — and NEVER a platform permission.
        RoleRef orgAdmin = orgRole(orgId, Roles.ORG_ADMIN);
        assertThat(orgAdmin.getPermissionNames())
                .containsAll(Permissions.tenantGrantable())
                .doesNotContain(Permissions.ORG_CREATE, Permissions.ORG_UPDATE, Permissions.ORG_DELETE);
        assertThat(orgRole(orgId, Roles.GROUP_ADMIN).getPermissionNames())
                .contains(Permissions.USER_READ).doesNotContain(Permissions.USER_CREATE);
    }

    @Test
    void provisioningIsIdempotent() {
        UUID orgId = org();

        Map<String, UUID> first = rbacService.provisionBaselineRoles(orgId);
        Map<String, UUID> again = rbacService.provisionBaselineRoles(orgId);

        assertThat(again).isEqualTo(first);
        assertThat(orgRoles(orgId)).hasSize(3);
    }

    @Test
    void theOrgsOrgAdminRoleIsAssignedTheAdminConsole() {
        UUID orgId = org();
        UUID orgAdminRoleId = orgRole(orgId, Roles.ORG_ADMIN).getId();
        String consoleId = clients.findByClientId(AdminPortalSeeder.CLIENT_ID).getId();

        // Assignment resolution matches role IDS, so the org's own ROLE_ORG_ADMIN needs its own row.
        assertThat(orgContext.callInOrg(orgId, () -> applications.assignmentsForApp(AppType.OIDC, consoleId)))
                .anyMatch(assignment -> assignment.subjectId().equals(orgAdminRoleId.toString()));
    }

    @Test
    void aTenantUserIsAssignedTheOrgsOwnRolesByName() {
        UUID orgId = org();
        String username = "u-" + UUID.randomUUID().toString().substring(0, 8);

        UserAccount user = userService.createUser(new NewUser(username, username + "@example.com", "U",
                "S3cret!pw", Set.of(Roles.USER, Roles.ORG_ADMIN)), orgId);

        // The names resolved to the ORG's provisioned roles, not the global fallbacks.
        for (String name : List.of(Roles.USER, Roles.ORG_ADMIN)) {
            assertThat(roleService.members(orgRole(orgId, name).getId()))
                    .anyMatch(member -> member.getId().equals(user.getId()));
            assertThat(roleService.members(roleService.findByName(name).orElseThrow().getId()))
                    .noneMatch(member -> member.getId().equals(user.getId()));
        }
    }

    @Test
    void theOrgSystemRoleNameIsEmittedAsAnAuthority() {
        UUID orgId = org();
        String username = "u-" + UUID.randomUUID().toString().substring(0, 8);
        userService.createUser(new NewUser(username, username + "@example.com", "U", "S3cret!pw",
                Set.of(Roles.USER, Roles.ORG_ADMIN)), orgId);

        UserDetails details = loginScope.within(orgId,
                () -> orgContext.callInOrg(orgId, () -> userDetailsService.loadUserByUsername(username)));

        // The well-known name (console entry / authorization checks) AND the role's permissions are granted.
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains(Roles.ORG_ADMIN, Roles.USER, Permissions.USER_READ)
                .doesNotContain(Permissions.ORG_CREATE);
    }

    @Test
    void backfillMigratesAnExistingMemberOffTheGlobalBaselineRole() {
        UUID orgId = org();
        String username = "legacy-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = userService.createUser(new NewUser(username, username + "@example.com", "U",
                "S3cret!pw", Set.of()), orgId);
        RoleRef globalUserRole = roleService.findByName(Roles.USER).orElseThrow();
        roleService.addMember(globalUserRole.getId(), user.getId()); // the pre-per-org-roles state

        provisioner.backfillExistingOrganizations();

        assertThat(roleService.members(globalUserRole.getId()))
                .noneMatch(member -> member.getId().equals(user.getId()));
        assertThat(roleService.members(orgRole(orgId, Roles.USER).getId()))
                .anyMatch(member -> member.getId().equals(user.getId()));
    }

    @Test
    void backfillLeavesHoldersOfOtherTiersOnTheGlobalRole() {
        UUID orgId = org();
        UUID otherOrgId = org();
        RoleRef globalUserRole = roleService.findByName(Roles.USER).orElseThrow();
        // A GLOBAL (platform) account and ANOTHER tenant's member both hold the global baseline role. The
        // migration must touch only THIS org's members — else it drags foreign accounts into the org's role
        // (cross-tenant contamination) and needlessly terminates their sessions.
        String globalName = "platform-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount globalUser = userService.createUser(new NewUser(globalName, globalName + "@example.com",
                "P", "S3cret!pw", Set.of(Roles.USER)));
        String foreignName = "foreign-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount foreignUser = userService.createUser(new NewUser(foreignName, foreignName + "@example.com",
                "F", "S3cret!pw", Set.of()), otherOrgId);
        roleService.addMember(globalUserRole.getId(), foreignUser.getId());

        provisioner.backfillExistingOrganizations();

        assertThat(roleService.members(globalUserRole.getId()))
                .anyMatch(member -> member.getId().equals(globalUser.getId()));
        assertThat(roleService.members(orgRole(orgId, Roles.USER).getId()))
                .noneMatch(member -> member.getId().equals(globalUser.getId()))
                .noneMatch(member -> member.getId().equals(foreignUser.getId()));
        // The foreign member migrated to its OWN org's role, never to this one.
        assertThat(roleService.members(orgRole(otherOrgId, Roles.USER).getId()))
                .anyMatch(member -> member.getId().equals(foreignUser.getId()));
    }

    @Test
    void aSecondBackfillMigratesNobodyAndAddsNoConsoleAssignment() {
        UUID orgId = org();
        String username = "legacy-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = userService.createUser(new NewUser(username, username + "@example.com", "U",
                "S3cret!pw", Set.of()), orgId);
        roleService.addMember(roleService.findByName(Roles.USER).orElseThrow().getId(), user.getId());

        provisioner.backfillExistingOrganizations();
        provisioner.backfillExistingOrganizations(); // idempotent: no re-migration, no duplicate rows

        assertThat(roleService.members(orgRole(orgId, Roles.USER).getId()))
                .filteredOn(member -> member.getId().equals(user.getId())).hasSize(1);
        assertThat(orgRoles(orgId)).hasSize(3);
        assertThat(consoleAssignmentsFor(orgId, orgRole(orgId, Roles.ORG_ADMIN).getId())).hasSize(1);
    }

    @Test
    void aTenantUserKeepsResolvingAGlobalOnlyRoleByName() {
        // The org has no copy of this name, so the assignment falls back to the GLOBAL role (the javadoc's
        // "names the org has no copy of" branch).
        UUID orgId = org();
        String roleName = "ROLE_SUPPORT_" + UUID.randomUUID().toString().substring(0, 8);
        RoleRef globalOnly = roleService.create(roleName, Set.of(Permissions.USER_READ));
        String username = "u-" + UUID.randomUUID().toString().substring(0, 8);

        UserAccount user = userService.createUser(new NewUser(username, username + "@example.com", "U",
                "S3cret!pw", Set.of(roleName)), orgId);

        assertThat(roleService.members(globalOnly.getId()))
                .anyMatch(member -> member.getId().equals(user.getId()));
    }

    @Test
    void updatingATenantUsersRolesResolvesTheOrgsOwnRole() {
        UUID orgId = org();
        String username = "u-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = userService.createUser(new NewUser(username, username + "@example.com", "U",
                "S3cret!pw", Set.of(Roles.USER)), orgId);

        userService.updateUser(user.getId(),
                new UserUpdate("U", username + "@example.com", true, Set.of(Roles.ORG_ADMIN)));

        // updateUser resolves in the USER's org, exactly like createUser — never onto the global role.
        assertThat(roleService.members(orgRole(orgId, Roles.ORG_ADMIN).getId()))
                .anyMatch(member -> member.getId().equals(user.getId()));
        assertThat(roleService.members(roleService.findByName(Roles.ORG_ADMIN).orElseThrow().getId()))
                .noneMatch(member -> member.getId().equals(user.getId()));
    }

    @Test
    void aNonSystemRoleSquattingABaselineNameBlocksProvisioningInsteadOfElevatingItsHolders() {
        // A legacy tenant role squatting a baseline name (possible before the name reservation) must NOT be
        // adopted: marking it system + granting it the tenant-admin catalog would silently elevate every
        // current holder. Provisioning that ONE role is skipped (reported), and the role stays as it was.
        UUID orgId = org();
        UUID squatterId = orgRole(orgId, Roles.GROUP_ADMIN).getId();
        demoteToLegacyCustomRole(squatterId); // as it would look on a pre-feature database

        Map<String, UUID> provisioned = rbacService.provisionBaselineRoles(orgId);

        assertThat(provisioned).doesNotContainKey(Roles.GROUP_ADMIN);
        assertThat(provisioned).containsKeys(Roles.USER, Roles.ORG_ADMIN);
        assertThat(roleService.permissionNames(squatterId)).isEmpty(); // never granted the baseline
        assertThat(orgContext.callInOrg(orgId, () -> roleService.findById(squatterId).orElseThrow().isSystem()))
                .isFalse(); // never marked system, so its name is never emitted as an authority
    }

    /** Rewrites a provisioned baseline role into the legacy shape: a plain tenant role, no baseline grants. */
    private void demoteToLegacyCustomRole(UUID roleId) {
        ownerJdbc().update("delete from role_permission where role_id = ?", roleId);
        ownerJdbc().update("update role set system = false where id = ?", roleId);
    }

    private List<AppAssignmentView> consoleAssignmentsFor(UUID orgId, UUID roleId) {
        String consoleId = clients.findByClientId(AdminPortalSeeder.CLIENT_ID).getId();
        return orgContext.callInOrg(orgId, () -> applications.assignmentsForApp(AppType.OIDC, consoleId))
                .stream().filter(assignment -> assignment.subjectId().equals(roleId.toString())).toList();
    }
}
