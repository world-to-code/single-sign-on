package com.example.sso.admin;

import com.example.sso.admin.internal.group.application.GroupAdminService;
import com.example.sso.admin.internal.role.application.RoleAdminService;
import com.example.sso.admin.internal.user.application.UserAdminService;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;
import com.example.sso.user.account.UserUpdate;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.deny.DenySpec;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.group.GroupRequest;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.rbac.Permissions;
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
 * Real-DB coverage for the lift ceiling on every admin verb that drops a membership a deny rides on.
 *
 * <p>Runs inside a tenant, so the ceiling's deny read goes through the RLS-FORCEd deny table under the org's
 * own context — an RLS miss would read as "no deny" and fail OPEN, which a mocked store cannot show. Two org
 * admins share the same apex ({@code ROLE_ORG_ADMIN}): the AUTHOR of the deny may lift it, the PEER may not
 * (equal apexes do not strictly dominate). Everything else about the two actors is identical, so a refusal of
 * the peer and a success of the author isolate the ceiling.
 */
class MembershipDropDenyCeilingIT extends AbstractIntegrationTest {

    private static final String REFUSAL = "user.membership.denyGoverned";
    private static final String DENIED = Permissions.GROUP_READ;

    @Autowired RoleAdminService roleAdmin;
    @Autowired UserAdminService userAdmin;
    @Autowired GroupAdminService groupAdmin;
    @Autowired RoleService roles;
    @Autowired UserGroupService groups;
    @Autowired DenyService denies;
    @Autowired UserService users;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private final List<Runnable> cleanups = new ArrayList<>();

    private UUID org;
    private Actor author;
    private Actor peer;
    private UUID victim;
    private String victimEmail;
    private UUID role;
    private String roleName;

    @BeforeEach
    void seedTenant() {
        String slug = "mdc-" + shortId();
        org = organizations.create(new NewOrganization(slug, "Membership deny ceiling IT")).id();
        UUID orgId = org;
        cleanups.add(() -> {
            ownerJdbc().update("delete from principal_permission_deny where org_id = ?", orgId);
            ownerJdbc().update("delete from user_group where org_id = ?", orgId);
            ownerJdbc().update("delete from app_user where org_id = ?", orgId);
            ownerJdbc().update("delete from organization where id = ?", orgId);
        });
        UUID orgAdminRole = roles.findByName(Roles.ORG_ADMIN, org).orElseThrow().getId();
        author = orgAdmin(orgAdminRole);
        peer = orgAdmin(orgAdminRole);

        // A custom role wired BELOW the org admin apex, so both admins may manage and assign it.
        roleName = "ROLE_MDC_" + shortId().toUpperCase();
        role = orgContext.callInOrg(org, () -> roles.create(roleName, Set.of(DENIED), Set.of(orgAdminRole)).getId());

        victimEmail = "mdc-v-" + shortId() + "@example.com";
        victim = orgContext.callInOrg(org, () -> users.createUser(
                new NewUser(victimEmail.substring(0, victimEmail.indexOf('@')), victimEmail, "Victim",
                        "S3cret!pw9", Set.of(Roles.USER)), org).getId());
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        ownerJdbc().update("delete from audit_event where reason = ?", REFUSAL);
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    // --- DELETE /roles/{id}/members/{userId} ---------------------------------------------------------

    @Test
    void removeRoleMemberIsRefusedForAnAdminWhoCannotLiftTheRoleDeny() {
        grantRole(role, victim);
        denyBy(author, DenySubjectKind.ROLE, role);

        assertRefusedAs(peer, () -> roleAdmin.removeRoleMember(role, victim));

        assertThat(roleMemberships(role, victim)).isOne();
        assertThat(denyRows(role)).isOne();
    }

    @Test
    void removeRoleMemberSucceedsForTheDenysAuthor() {
        grantRole(role, victim);
        denyBy(author, DenySubjectKind.ROLE, role);

        assertAllowedAs(author, () -> roleAdmin.removeRoleMember(role, victim));

        assertThat(roleMemberships(role, victim)).isZero();
    }

    // --- PUT /users/{id} omitting a held role --------------------------------------------------------

    @Test
    void userUpdateOmittingTheRoleIsRefusedForAnAdminWhoCannotLiftTheRoleDeny() {
        grantRole(role, victim);
        denyBy(author, DenySubjectKind.ROLE, role);

        assertRefusedAs(peer, () -> userAdmin.updateUser(victim, victimUpdate(Set.of(Roles.USER))));

        assertThat(roleMemberships(role, victim)).isOne();
        assertThat(denyRows(role)).isOne();
    }

    @Test
    void userUpdateOmittingTheRoleSucceedsForTheDenysAuthor() {
        grantRole(role, victim);
        denyBy(author, DenySubjectKind.ROLE, role);

        assertAllowedAs(author, () -> userAdmin.updateUser(victim, victimUpdate(Set.of(Roles.USER))));

        assertThat(roleMemberships(role, victim)).isZero();
    }

    @Test
    void userUpdateKeepingTheRoleNeedsNoLiftAuthority() {
        grantRole(role, victim);
        denyBy(author, DenySubjectKind.ROLE, role);

        assertAllowedAs(peer, () -> userAdmin.updateUser(victim, victimUpdate(Set.of(Roles.USER, roleName))));

        assertThat(roleMemberships(role, victim)).isOne();
    }

    // --- DELETE /roles/{id} --------------------------------------------------------------------------

    @Test
    void deleteRoleIsRefusedForAnAdminWhoCannotLiftTheRoleDeny() {
        grantRole(role, victim);
        denyBy(author, DenySubjectKind.ROLE, role);

        assertRefusedAs(peer, () -> roleAdmin.deleteRole(role));

        assertThat(roleRows(role)).isOne();
        assertThat(roleMemberships(role, victim)).isOne();
        assertThat(denyRows(role)).isOne();
    }

    @Test
    void deleteRoleSucceedsForTheDenysAuthor() {
        grantRole(role, victim);
        denyBy(author, DenySubjectKind.ROLE, role);

        assertAllowedAs(author, () -> roleAdmin.deleteRole(role));

        assertThat(roleRows(role)).isZero();
        assertThat(roleMemberships(role, victim)).isZero();
    }

    // --- PUT /groups/{id}/roles undelegating ---------------------------------------------------------

    @Test
    void undelegatingTheRoleIsRefusedForAnAdminWhoCannotLiftTheRoleDeny() {
        UUID group = groupWith(Set.of(victim));
        delegate(group, role);
        denyBy(author, DenySubjectKind.ROLE, role);

        assertRefusedAs(peer, () -> groupAdmin.setRoles(group, Set.of()));

        assertThat(delegations(group, role)).isOne();
        assertThat(denyRows(role)).isOne();
    }

    @Test
    void undelegatingTheRoleSucceedsForTheDenysAuthor() {
        UUID group = groupWith(Set.of(victim));
        delegate(group, role);
        denyBy(author, DenySubjectKind.ROLE, role);

        assertAllowedAs(author, () -> groupAdmin.setRoles(group, Set.of()));

        assertThat(delegations(group, role)).isZero();
    }

    // --- PUT /groups/{id} dropping members -----------------------------------------------------------

    @Test
    void groupUpdateOmittingMembersIsRefusedForAnAdminWhoCannotLiftTheGroupDeny() {
        UUID group = groupWith(Set.of(victim));
        denyBy(author, DenySubjectKind.GROUP, group);

        assertRefusedAs(peer, () -> groupAdmin.update(group, new GroupRequest(groupName(group), null, null, null)));

        assertThat(groupMemberships(group, victim)).isOne();
        assertThat(denyRows(group)).isOne();
    }

    @Test
    void groupUpdateOmittingMembersSucceedsForTheDenysAuthor() {
        UUID group = groupWith(Set.of(victim));
        denyBy(author, DenySubjectKind.GROUP, group);

        assertAllowedAs(author, () -> groupAdmin.update(group, new GroupRequest(groupName(group), null, null, null)));

        assertThat(groupMemberships(group, victim)).isZero();
    }

    @Test
    void groupRenameKeepingEveryMemberNeedsNoLiftAuthority() {
        UUID group = groupWith(Set.of(victim));
        denyBy(author, DenySubjectKind.GROUP, group);
        String renamed = "mdc-renamed-" + shortId();

        assertAllowedAs(peer, () -> groupAdmin.update(group,
                new GroupRequest(renamed, null, null, List.of(victim.toString()))));

        assertThat(groupName(group)).isEqualTo(renamed);
        assertThat(groupMemberships(group, victim)).isOne();
    }

    // --- DELETE /groups/{id} -------------------------------------------------------------------------

    @Test
    void groupDeleteIsRefusedForAnAdminWhoCannotLiftTheGroupDeny() {
        UUID group = groupWith(Set.of(victim));
        denyBy(author, DenySubjectKind.GROUP, group);

        assertRefusedAs(peer, () -> groupAdmin.delete(group));

        assertThat(groupRows(group)).isOne();
        assertThat(groupMemberships(group, victim)).isOne();
        assertThat(denyRows(group)).isOne();
    }

    @Test
    void groupDeleteSucceedsForTheDenysAuthor() {
        UUID group = groupWith(Set.of(victim));
        denyBy(author, DenySubjectKind.GROUP, group);

        assertAllowedAs(author, () -> groupAdmin.delete(group));

        assertThat(groupRows(group)).isZero();
        assertThat(groupMemberships(group, victim)).isZero();
    }

    // --- acting ---------------------------------------------------------------------------------------

    private void assertRefusedAs(Actor actor, Runnable call) {
        actAs(actor);
        assertThatThrownBy(() -> orgContext.runInOrg(org, call))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(REFUSAL);
    }

    private void assertAllowedAs(Actor actor, Runnable call) {
        actAs(actor);
        assertThatCode(() -> orgContext.runInOrg(org, call)).doesNotThrowAnyException();
    }

    /** The actor's LIVE effective authorities as the session would carry them after login into the org. */
    private void actAs(Actor actor) {
        List<SimpleGrantedAuthority> authorities = orgContext.callInOrg(org, () -> users.effectiveAuthorities(actor.id))
                .stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.username, null, authorities));
    }

    // --- fixtures --------------------------------------------------------------------------------------

    private Actor orgAdmin(UUID orgAdminRole) {
        String username = "mdc-admin-" + shortId();
        UUID id = orgContext.callInOrg(org, () -> users.createUser(new NewUser(username, username + "@example.com",
                "Admin", "S3cret!pw9", Set.of(Roles.USER)), org).getId());
        organizations.addMember(org, id); // org admin reach over the org's groups needs the membership too
        grantRole(orgAdminRole, id);
        return new Actor(id, username);
    }

    private void grantRole(UUID roleId, UUID userId) {
        orgContext.runInOrg(org, () -> roles.addMember(roleId, userId));
    }

    /** Authored through the real deny service, so the created_by / writer-apex stamp is production's. */
    private void denyBy(Actor actor, DenySubjectKind kind, UUID subjectId) {
        actAs(actor);
        try {
            orgContext.runInOrg(org, () -> denies.create(new DenySpec(kind, subjectId, DENIED)));
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(denyRows(subjectId)).isOne();
    }

    private UUID groupWith(Set<UUID> members) {
        return orgContext.callInOrg(org, () -> UUID.fromString(
                groups.create(new GroupSpec("mdc-g-" + shortId(), null, null, members)).id()));
    }

    private void delegate(UUID group, UUID roleId) {
        orgContext.runInOrg(org, () -> groups.setRoles(group, Set.of(roleId)));
    }

    private UserUpdate victimUpdate(Set<String> roleNames) {
        return new UserUpdate("Victim", victimEmail, true, roleNames);
    }

    // --- persisted state, read as the owner (arrange/inspect only) ------------------------------------

    private int roleMemberships(UUID roleId, UUID userId) {
        return count("select count(*) from app_user_role where role_id = ? and user_id = ?", roleId, userId);
    }

    private int groupMemberships(UUID groupId, UUID userId) {
        return count("select count(*) from user_group_member where group_id = ? and user_id = ?", groupId, userId);
    }

    private int delegations(UUID groupId, UUID roleId) {
        return count("select count(*) from group_role where group_id = ? and role_id = ?", groupId, roleId);
    }

    private int denyRows(UUID subjectId) {
        return count("select count(*) from principal_permission_deny where subject_id = ? and pattern = ?",
                subjectId, DENIED);
    }

    private int roleRows(UUID roleId) {
        return count("select count(*) from role where id = ?", roleId);
    }

    private int groupRows(UUID groupId) {
        return count("select count(*) from user_group where id = ?", groupId);
    }

    private String groupName(UUID groupId) {
        return ownerJdbc().queryForObject("select name from user_group where id = ?", String.class, groupId);
    }

    private int count(String sql, Object... args) {
        return ownerJdbc().queryForObject(sql, Integer.class, args);
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Actor(UUID id, String username) {
    }
}
