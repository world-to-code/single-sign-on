package com.example.sso.admin;

import com.example.sso.admin.internal.role.application.RoleAdminService;
import com.example.sso.admin.internal.role.application.RoleView;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-DB coverage for {@code RoleAdminService.requireRoleInTier} and the tier-filtered {@code listRoles}.
 *
 * <p>The role table's RLS deliberately exposes GLOBAL rows to a tenant read, and {@code role_permission} has no
 * RLS at all, so nothing in the database stops a tenant admin from rewriting or deleting a shared role — only
 * the service's tier check does. The mocked unit test cannot show that premise; this one asserts it and then
 * asserts the persisted rows survive the attempt.
 */
class RoleTierConfinementIT extends AbstractIntegrationTest {

    private static final String ROLE_NOT_FOUND = "user.role.notFound";
    private static final Set<String> ORIGINAL_PERMISSIONS = Set.of(Permissions.GROUP_READ);
    private static final Set<String> REWRITTEN_PERMISSIONS = Set.of(Permissions.GROUP_READ, Permissions.USER_READ);

    @Autowired RoleAdminService roleAdmin;
    @Autowired RoleService roles;
    @Autowired UserService users;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private final List<Runnable> cleanups = new ArrayList<>();

    private UUID org;
    private UUID otherOrg;
    private UUID globalRole;
    private UUID otherOrgRole;
    private UUID ownRole;

    @BeforeEach
    void seed() {
        org = org();
        otherOrg = org();
        UUID orgAdminRole = roles.findByName(Roles.ORG_ADMIN, org).orElseThrow().getId();
        actAsTenantAdmin(orgAdmin(orgAdminRole));

        globalRole = orgContext.callAsPlatform(() -> roles.create(roleName("G"), ORIGINAL_PERMISSIONS).getId());
        UUID createdGlobal = globalRole;
        cleanups.add(() -> ownerJdbc().update("delete from role where id = ?", createdGlobal));
        otherOrgRole = orgContext.callInOrg(otherOrg, () -> roles.create(roleName("O"), ORIGINAL_PERMISSIONS).getId());
        // Below the org admin apex, so the tenant admin may legitimately manage it.
        ownRole = orgContext.callInOrg(org, () ->
                roles.create(roleName("OWN"), ORIGINAL_PERMISSIONS, Set.of(orgAdminRole)).getId());
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    // --- a GLOBAL role ---------------------------------------------------------------------------------

    @Test
    void updatingAGlobalRoleIsNotFoundAndLeavesItsPermissionsUnchanged() {
        // The premise: RLS alone would hand the global row to this tenant.
        assertThat(orgContext.callInOrg(org, () -> roles.findById(globalRole))).isPresent();

        assertThatThrownBy(() -> orgContext.runInOrg(org,
                () -> roleAdmin.updateRole(globalRole, "hijacked-" + shortId(), REWRITTEN_PERMISSIONS)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(ROLE_NOT_FOUND);

        assertThat(persistedPermissions(globalRole)).containsExactlyInAnyOrderElementsOf(ORIGINAL_PERMISSIONS);
        assertThat(persistedName(globalRole)).doesNotStartWith("hijacked-");
    }

    @Test
    void deletingAGlobalRoleIsNotFoundAndLeavesItInPlace() {
        assertThatThrownBy(() -> orgContext.runInOrg(org, () -> roleAdmin.deleteRole(globalRole)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(ROLE_NOT_FOUND);

        assertThat(roleRows(globalRole)).isOne();
    }

    // --- ANOTHER org's role ----------------------------------------------------------------------------

    @Test
    void updatingAnotherOrgsRoleIsNotFoundAndLeavesItsPermissionsUnchanged() {
        assertThatThrownBy(() -> orgContext.runInOrg(org,
                () -> roleAdmin.updateRole(otherOrgRole, "hijacked-" + shortId(), REWRITTEN_PERMISSIONS)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(ROLE_NOT_FOUND);

        assertThat(persistedPermissions(otherOrgRole)).containsExactlyInAnyOrderElementsOf(ORIGINAL_PERMISSIONS);
        assertThat(persistedName(otherOrgRole)).doesNotStartWith("hijacked-");
    }

    @Test
    void deletingAnotherOrgsRoleIsNotFoundAndLeavesItInPlace() {
        assertThatThrownBy(() -> orgContext.runInOrg(org, () -> roleAdmin.deleteRole(otherOrgRole)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(ROLE_NOT_FOUND);

        assertThat(roleRows(otherOrgRole)).isOne();
    }

    // --- the tenant's OWN role -------------------------------------------------------------------------

    @Test
    void updatingItsOwnOrgsRoleIsAllowedAndPersisted() {
        String renamed = roleName("RENAMED");

        assertThatCode(() -> orgContext.runInOrg(org,
                () -> roleAdmin.updateRole(ownRole, renamed, REWRITTEN_PERMISSIONS)))
                .doesNotThrowAnyException();

        assertThat(persistedPermissions(ownRole)).containsExactlyInAnyOrderElementsOf(REWRITTEN_PERMISSIONS);
        assertThat(persistedName(ownRole)).isEqualTo(renamed);
    }

    @Test
    void deletingItsOwnOrgsRoleIsAllowedAndPersisted() {
        assertThatCode(() -> orgContext.runInOrg(org, () -> roleAdmin.deleteRole(ownRole)))
                .doesNotThrowAnyException();

        assertThat(roleRows(ownRole)).isZero();
    }

    // --- listing ---------------------------------------------------------------------------------------

    @Test
    void listRolesShowsTheTenantNoGlobalOrForeignRole() {
        List<UUID> listed = orgContext.callInOrg(org, () -> roleAdmin.listRoles()).stream()
                .map(RoleView::id).map(UUID::fromString).toList();

        // Non-vacuous: RLS itself hands this tenant the global role, so only the service filter can hide it.
        assertThat(orgContext.callInOrg(org, () -> roles.findAll())).extracting(RoleRef::getId).contains(globalRole);
        List<UUID> globalIds = ownerJdbc().queryForList("select id from role where org_id is null", UUID.class);
        assertThat(listed).contains(ownRole).doesNotContain(otherOrgRole).doesNotContainAnyElementsOf(globalIds);
    }

    // --- fixtures ----------------------------------------------------------------------------------------

    private UUID org() {
        String slug = "rtc-" + shortId();
        UUID id = organizations.create(new NewOrganization(slug, "Role tier confinement IT")).id();
        cleanups.add(() -> {
            ownerJdbc().update("delete from app_user where org_id = ?", id);
            ownerJdbc().update("delete from organization where id = ?", id);
        });
        return id;
    }

    private TenantAdmin orgAdmin(UUID orgAdminRole) {
        String username = "rtc-admin-" + shortId();
        UUID id = orgContext.callInOrg(org, () -> users.createUser(new NewUser(username, username + "@example.com",
                "Admin", "S3cret!pw9", Set.of(Roles.USER)), org).getId());
        organizations.addMember(org, id);
        orgContext.runInOrg(org, () -> roles.addMember(orgAdminRole, id));
        return new TenantAdmin(id, username);
    }

    /** The admin's live effective authorities, as the session carries them after login into the org. */
    private void actAsTenantAdmin(TenantAdmin admin) {
        List<SimpleGrantedAuthority> authorities = orgContext.callInOrg(org, () -> users.effectiveAuthorities(admin.id))
                .stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin.username, null, authorities));
    }

    private Set<String> persistedPermissions(UUID roleId) {
        return Set.copyOf(ownerJdbc().queryForList("select p.name from role_permission rp "
                + "join permission p on p.id = rp.permission_id where rp.role_id = ?", String.class, roleId));
    }

    private String persistedName(UUID roleId) {
        return ownerJdbc().queryForObject("select name from role where id = ?", String.class, roleId);
    }

    private int roleRows(UUID roleId) {
        return ownerJdbc().queryForObject("select count(*) from role where id = ?", Integer.class, roleId);
    }

    private String roleName(String tag) {
        return "ROLE_RTC_" + tag + "_" + shortId().toUpperCase();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record TenantAdmin(UUID id, String username) {
    }
}
