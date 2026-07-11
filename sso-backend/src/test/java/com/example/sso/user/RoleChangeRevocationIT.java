package com.example.sso.user;

import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a role's permissions change, the sessions of everyone whose EFFECTIVE authorities changed must end —
 * not only the role's direct holders but the holders of the same-tier ancestor roles that INHERIT it. This
 * pins that editing a tenant's {@code ROLE_GROUP_ADMIN} revokes its holders AND the tenant's
 * {@code ROLE_ORG_ADMIN} holders (which inherit it), while deliberately NOT revoking the platform
 * {@code ROLE_ADMIN} holders (the cross-tier ancestor) — ROLE_ADMIN self-heals to the full catalog, so its
 * effective authorities are unchanged and logging out every super-admin on a tenant edit would be wrong.
 */
@RecordApplicationEvents
class RoleChangeRevocationIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;
    @Autowired
    RoleService roleService;
    @Autowired
    UserService userService;
    @Autowired
    OrgContext orgContext;
    @Autowired
    ApplicationEvents events;

    @Test
    void editingATenantRoleRevokesItsHoldersAndSameTierAncestorHoldersButNotThePlatformAdmin() {
        UUID org = organizations.create(new NewOrganization("rev-" + suffix(), "rev")).id();
        UUID groupAdminRole = orgRoleId(org, Roles.GROUP_ADMIN);

        String groupAdminHolder = tenantUser(org, Roles.GROUP_ADMIN); // direct holder of the edited role
        String orgAdminHolder = tenantUser(org, Roles.ORG_ADMIN);     // ancestor holder (inherits GROUP_ADMIN)
        String platformAdmin = globalAdmin();                          // cross-tier ancestor — must NOT be revoked

        orgContext.runInOrg(org, () -> roleService.updateRole(
                groupAdminRole, Roles.GROUP_ADMIN, Set.of(Permissions.USER_READ, Permissions.USER_UPDATE)));

        List<String> revoked = events.stream(UserAccessChangedEvent.class)
                .map(UserAccessChangedEvent::username).toList();
        assertThat(revoked).contains(groupAdminHolder, orgAdminHolder);
        assertThat(revoked).doesNotContain(platformAdmin);
    }

    private UUID orgRoleId(UUID org, String name) {
        return orgContext.callInOrg(org, () -> roleService.findAll()).stream()
                .filter(role -> org.equals(role.getOrgId()) && name.equals(role.getName()))
                .map(RoleRef::getId).findFirst().orElseThrow();
    }

    private String tenantUser(UUID org, String roleName) {
        String username = "rev-" + suffix();
        userService.createUser(new NewUser(username, username + "@example.com", "R", "S3cret!pw",
                Set.of(roleName)), org);
        return username;
    }

    private String globalAdmin() {
        String username = "rev-super-" + suffix();
        userService.createUser(new NewUser(username, username + "@example.com", "S", "S3cret!pw",
                Set.of(Roles.ADMIN)));
        return username;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
