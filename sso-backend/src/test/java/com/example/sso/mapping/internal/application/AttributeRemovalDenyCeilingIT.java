package com.example.sso.mapping.internal.application;

import com.example.sso.admin.internal.group.application.GroupAdminService;
import com.example.sso.admin.internal.role.application.RoleAdminService;
import com.example.sso.admin.internal.user.application.UserAdminService;
import com.example.sso.mapping.MappingRuleService;
import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The REMOVAL-side deny ceiling end to end, against a real database.
 *
 * <p>The mirror of {@code AttributeGrantGuardIT}, and it has to exist for the same reason that one does: the
 * mock-level test stubs the port, so every refusing branch of
 * {@code AttributeValueGrantGuardAdapter.keysWhoseRemovalLiftsDeny} → {@code DenyService.mayLiftEveryDenyOn}
 * → the real deny rows was unexecuted. Worse than unexecuted: the natural failure mode is FAIL-OPEN. The
 * verdict is an {@code allMatch} over rows fetched from an RLS-FORCEd table, so a wrong subject kind, a
 * wrong id, or rows hidden by the acting tier all produce an empty list and a confident "removable".
 *
 * <p>What it pins: removal is the one direction the grant model calls harmless — mapping-rule operators are
 * positive-only, so taking a value away can only retract a grant. A DENY inverts that. It SUBTRACTS from
 * whoever holds the membership a rule confers, so deleting the attribute drops the membership and hands the
 * withheld permission back — a lift, performed by someone the lift authority refuses.
 */
class AttributeRemovalDenyCeilingIT extends AbstractIntegrationTest {

    @Autowired AttributeService attributes;
    @Autowired MappingRuleService mappingRules;
    @Autowired RoleService roles;
    @Autowired RoleAdminService roleAdmin;
    @Autowired UserAdminService userAdmin;
    @Autowired GroupAdminService groupAdmin;
    @Autowired UserGroupService groups;
    @Autowired DenyService denies;
    @Autowired UserService users;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();
    private final List<UUID> createdOrgs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        orgContext.runAsPlatform(() -> {
            ownerJdbc().update("delete from mapping_rule_condition");
            ownerJdbc().update("delete from mapping_rule");
            ownerJdbc().update("delete from principal_permission_deny");
            ownerJdbc().update("delete from audit_event where reason = 'user.membership.denyGoverned'");
            createdUsers.forEach(id ->
                    ownerJdbc().update("delete from entity_attribute where entity_id = ?", id.toString()));
            createdGroups.forEach(groups::delete);
            createdUsers.forEach(users::delete);
            createdRoles.forEach(roles::deleteRole);
        });
        createdOrgs.forEach(organizations::delete);
        createdUsers.clear();
        createdRoles.clear();
        createdGroups.clear();
        createdOrgs.clear();
    }

    /**
     * The attack the ceiling exists for: a tenant admin cannot delete the attribute that keeps a person in a
     * denied role, because doing so would lift a deny they could not lift directly.
     */
    @Test
    void aTenantAdminCannotRemoveAKeyWhoseLossWouldLiftADenyTheyCannotLift() {
        String key = "employment-" + suffix();
        String admin = superAdmin();
        UUID role = privilegedRoleBy(admin);
        ruleForRoleBy(admin, key, "contract", role);
        denyOnBy(admin, DenySubjectKind.ROLE, role, Permissions.ORG_CREATE);
        UUID org = org();
        String tenantAdmin = tenantUser(org);
        String victim = valueHolder(org, key, "contract");

        asTenantAdmin(tenantAdmin);
        assertThatThrownBy(() -> orgContext.runInOrg(org,
                () -> attributes.remove(EntityKind.USER, victim, key)))
                .isInstanceOf(ForbiddenException.class)
                // Named, not just typed: three ceilings can refuse this call, and a test that accepts any of
                // them would still pass if the one it is about were deleted.
                .hasMessage("metadata.attribute.denyGoverned");

        // The refusal is the ceiling, not blindness: the value is still there.
        assertThat(orgContext.callInOrg(org, () -> attributes.attributesOf(EntityKind.USER, victim)))
                .extracting(Attribute::key).containsExactly(key);
    }

    /** And the permissive direction: whoever COULD lift the deny may delete the key. */
    @Test
    void theSuperWhoAuthoredTheDenyMayRemoveTheSameKey() {
        String key = "employment-" + suffix();
        String admin = superAdmin();
        UUID role = privilegedRoleBy(admin);
        ruleForRoleBy(admin, key, "contract", role);
        denyOnBy(admin, DenySubjectKind.ROLE, role, Permissions.ORG_CREATE);
        String victim = platformValueHolder(key, "contract");

        asSuper(admin);
        assertThatCode(() -> orgContext.runAsPlatform(
                () -> attributes.remove(EntityKind.USER, victim, key)))
                .doesNotThrowAnyException();
    }

    /**
     * The indirection the first version of this guard missed: the rule confers a GROUP, and the deny sits on a
     * role that group DELEGATES. Losing the group loses the role, and a deny resolves against the holder's apex
     * roles — so asking only about the group's own denies let the removal lift it one step removed.
     */
    @Test
    void aDenyOnARoleTheTargetGroupDelegatesAlsoBlocksTheRemoval() {
        String key = "employment-" + suffix();
        String admin = superAdmin();
        UUID role = privilegedRoleBy(admin);
        UUID group = groupDelegating(admin, role);
        ruleForGroupBy(admin, key, "contract", group);
        denyOnBy(admin, DenySubjectKind.ROLE, role, Permissions.ORG_CREATE);
        UUID org = org();
        String tenantAdmin = tenantUser(org);
        String victim = valueHolder(org, key, "contract");

        asTenantAdmin(tenantAdmin);
        assertThatThrownBy(() -> orgContext.runInOrg(org,
                () -> attributes.remove(EntityKind.USER, victim, key)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("metadata.attribute.denyGoverned");
    }

    /** The bulk shape the profile move uses: one un-liftable key refuses the whole set, and nothing is deleted. */
    @Test
    void oneUnliftableKeyRefusesTheWholeRemovalSet() {
        String denied = "employment-" + suffix();
        String free = "team-" + suffix();
        String admin = superAdmin();
        UUID role = privilegedRoleBy(admin);
        ruleForRoleBy(admin, denied, "contract", role);
        denyOnBy(admin, DenySubjectKind.ROLE, role, Permissions.ORG_CREATE);
        UUID org = org();
        String tenantAdmin = tenantUser(org);
        String victim = valueHolder(org, denied, "contract");
        orgContext.runInOrg(org, () -> attributes.set(EntityKind.USER, victim, free, "infra"));

        asTenantAdmin(tenantAdmin);
        assertThatThrownBy(() -> orgContext.runInOrg(org,
                () -> attributes.removeAll(EntityKind.USER, victim, List.of(free, denied))))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("metadata.attribute.denyGoverned");

        assertThat(orgContext.callInOrg(org, () -> attributes.attributesOf(EntityKind.USER, victim)))
                .extracting(Attribute::key).containsExactlyInAnyOrder(denied, free);
    }

    /**
     * The ceiling belongs to writes that DELETE, and {@code add} is not one: it inserts a missing (key, value)
     * and leaves the existing values alone, so the rule reading {@code key=contract} still matches and the deny
     * riding on the conferred role never moves. Refusing it bought no safety and cost administrators a write.
     *
     * <p>The actor is a SECOND super, which is what separates this from the grant ceiling: they clear every
     * grant check a global admin clears, and still cannot lift a deny another super authored (equal apexes do
     * not strictly dominate). So the {@code set} half below can only be refused by the removal ceiling.
     */
    @Test
    void anAdditiveWriteIsAllowedWhereTheReplacingOneIsRefused() {
        String key = "employment-" + suffix();
        String author = superAdmin();
        String other = superAdmin();
        UUID role = privilegedRoleBy(author);
        ruleForRoleBy(author, key, "contract", role);
        denyOnBy(author, DenySubjectKind.ROLE, role, Permissions.ORG_CREATE);
        String victim = platformValueHolder(key, "contract");

        asSuper(other);
        assertThatCode(() -> orgContext.runAsPlatform(
                () -> attributes.add(EntityKind.USER, victim, key, "permanent")))
                .doesNotThrowAnyException();

        // set() narrows the key to exactly one value, so it WOULD delete "contract" and lift the deny.
        assertThatThrownBy(() -> orgContext.runAsPlatform(
                () -> attributes.set(EntityKind.USER, victim, key, "permanent")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("metadata.attribute.denyGoverned");

        assertThat(orgContext.callAsPlatform(() -> attributes.attributesOf(EntityKind.USER, victim)))
                .extracting(Attribute::value).containsExactlyInAnyOrder("contract", "permanent");
    }

    /** A key whose rule targets a role carrying NO deny still removes freely — the ceiling is about the loss. */
    @Test
    void aKeyWhoseTargetCarriesNoDenyIsStillRemovable() {
        String key = "team-" + suffix();
        String admin = superAdmin();
        UUID role = privilegedRoleBy(admin);
        ruleForRoleBy(admin, key, "infra", role); // rule exists, but nothing is denied on the role
        UUID org = org();
        String tenantAdmin = tenantUser(org);
        String victim = valueHolder(org, key, "infra");

        asTenantAdmin(tenantAdmin);
        assertThatCode(() -> orgContext.runInOrg(org,
                () -> attributes.remove(EntityKind.USER, victim, key)))
                .doesNotThrowAnyException();

        assertThat(orgContext.callInOrg(org, () -> attributes.attributesOf(EntityKind.USER, victim))).isEmpty();
    }

    /** The disclosure query answers the same way the write does — that agreement is why it exists. */
    @Test
    void theRefusalQueryReportsTheSameKeyTheWriteRefuses() {
        String key = "employment-" + suffix();
        String admin = superAdmin();
        UUID role = privilegedRoleBy(admin);
        ruleForRoleBy(admin, key, "contract", role);
        denyOnBy(admin, DenySubjectKind.ROLE, role, Permissions.ORG_CREATE);
        UUID org = org();
        String tenantAdmin = tenantUser(org);

        asTenantAdmin(tenantAdmin);
        assertThat(orgContext.callInOrg(org, () -> attributes.keysNotRemovable(EntityKind.USER, List.of(key))))
                .containsExactly(key);
    }

    /**
     * The same ceiling on the DIRECT route. Revoking the role by hand drops exactly the membership the
     * attribute deletion drops, so guarding only the indirect path left the leakier one open — and this is the
     * route an administrator reaches first.
     */
    @Test
    void aTenantAdminCannotRevokeTheRoleADenyRidesOnEither() {
        String admin = superAdmin();
        UUID role = privilegedRoleBy(admin);
        denyOnBy(admin, DenySubjectKind.ROLE, role, Permissions.ORG_CREATE);
        UUID org = org();
        String tenantAdmin = tenantUser(org);
        UUID victim = UUID.fromString(valueHolder(org, "unused-" + suffix(), "x"));
        orgContext.runAsPlatform(() -> roles.addMember(role, victim));

        asTenantAdmin(tenantAdmin);
        assertThatThrownBy(() -> orgContext.runInOrg(org, () -> roleAdmin.removeRoleMember(role, victim)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("user.membership.denyGoverned");

        assertThat(orgContext.callAsPlatform(() -> roles.members(role)))
                .extracting(UserAccount::getId).contains(victim);
    }

    /**
     * A refusal that leaves no trace is a control nobody can review. It matters more than usual here because
     * the refusal is itself a small disclosure — it tells an administrator who may not read denies that one
     * exists on this subject — and that cannot be withheld without withholding the reason the write failed.
     * Leaving it in the response and making a sweep DETECTABLE is the trade; the row is what makes it one.
     */
    @Test
    void aRefusedMembershipDropLeavesAFailureRowInTheTrail() {
        String admin = superAdmin();
        UUID role = privilegedRoleBy(admin);
        denyOnBy(admin, DenySubjectKind.ROLE, role, Permissions.ORG_CREATE);
        UUID org = org();
        String tenantAdmin = tenantUser(org);
        UUID victim = UUID.fromString(valueHolder(org, "unused-" + suffix(), "x"));
        orgContext.runAsPlatform(() -> roles.addMember(role, victim));

        asTenantAdmin(tenantAdmin);
        assertThatThrownBy(() -> orgContext.runInOrg(org, () -> roleAdmin.removeRoleMember(role, victim)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("user.membership.denyGoverned");

        // Written in its own transaction, so it is there despite the refusal rolling the caller's back.
        assertThat(ownerJdbc().queryForObject("""
                select count(*) from audit_event
                where type = 'AUTHORIZATION_DENIED' and success = false and reason = ?
                """, Integer.class, "user.membership.denyGoverned")).isEqualTo(1);
    }

    /**
     * Deleting the role is the widest version of the same act — every holder loses it at once, and the deny's
     * own subject stops existing. Guarding the per-member revoke and leaving this open would have made the
     * ceiling a detour: {@code role:delete} is tenant-grantable too.
     */
    @Test
    void aRoleCarryingADenyCannotBeDeletedByWhoeverCannotLiftIt() {
        String admin = superAdmin();
        String other = superAdmin();
        UUID role = privilegedRoleBy(admin);
        denyOnBy(admin, DenySubjectKind.ROLE, role, Permissions.ORG_CREATE);

        asSuper(other);
        assertThatThrownBy(() -> orgContext.runAsPlatform(() -> roleAdmin.deleteRole(role)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("user.membership.denyGoverned");

        assertThat(orgContext.callAsPlatform(() -> roles.findById(role))).isPresent();
    }

    /** And whoever could lift the deny may revoke the membership. */
    @Test
    void theSuperWhoAuthoredTheDenyMayRevokeTheSameRole() {
        String admin = superAdmin();
        UUID role = privilegedRoleBy(admin);
        denyOnBy(admin, DenySubjectKind.ROLE, role, Permissions.ORG_CREATE);
        UUID victim = UUID.fromString(platformValueHolder("unused-" + suffix(), "x"));
        orgContext.runAsPlatform(() -> roles.addMember(role, victim));

        asSuper(admin);
        assertThatCode(() -> orgContext.runAsPlatform(() -> roleAdmin.removeRoleMember(role, victim)))
                .doesNotThrowAnyException();
    }

    /**
     * The sibling verbs. The ceiling was installed on the route NAMED "remove member", and every one of these
     * reaches the same act at the same or a lower permission — so a control that only covered that route was a
     * detour, not a control. Enumerated here rather than trusted, because each is a separate service.
     */
    @Test
    void aRoleIsNotDroppableByOmittingItFromAUserUpdateEither() {
        String admin = superAdmin();
        UUID role = privilegedRoleBy(admin);
        denyOnBy(admin, DenySubjectKind.ROLE, role, Permissions.ORG_CREATE);
        UUID org = org();
        String tenantAdmin = tenantUser(org);
        UUID victim = UUID.fromString(valueHolder(org, "unused-" + suffix(), "x"));
        orgContext.runAsPlatform(() -> roles.addMember(role, victim));

        asTenantAdmin(tenantAdmin);
        // The gate on this route judges only the roles being ASSIGNED, so omitting one was authorized by nothing.
        assertThatThrownBy(() -> orgContext.runInOrg(org, () -> userAdmin.updateUser(victim,
                new UserUpdate("V", "v" + suffix() + "@example.com", true, Set.of(Roles.USER)))))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("user.membership.denyGoverned");

        assertThat(orgContext.callAsPlatform(() -> roles.members(role)))
                .extracting(UserAccount::getId).contains(victim);
    }

    /** A group replace that omits {@code memberUserIds} empties the group — the same drop, spelled as an update. */
    @Test
    void aGroupCannotBeEmptiedOrDeletedByWhoeverCannotLiftItsDeny() {
        String author = superAdmin();
        String other = superAdmin();
        UUID member = UUID.fromString(platformValueHolder("unused-" + suffix(), "x"));
        UUID group = groupWithMember(author, member);
        denyOnBy(author, DenySubjectKind.GROUP, group, Permissions.ORG_CREATE);

        asSuper(other);
        assertThatThrownBy(() -> orgContext.runAsPlatform(() ->
                groupAdmin.update(group, new GroupRequest("g" + suffix(), null, null, null))))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("user.membership.denyGoverned");
        assertThatThrownBy(() -> orgContext.runAsPlatform(() -> groupAdmin.delete(group)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("user.membership.denyGoverned");

        assertThat(orgContext.callAsPlatform(() -> groups.memberIdsOf(Set.of(group)))).contains(member);
    }

    /** A rename loses nobody, so it must not need the lift authority — the ceiling is about the loss. */
    @Test
    void aGroupUpdateThatKeepsEveryMemberNeedsNoLiftAuthority() {
        String author = superAdmin();
        String other = superAdmin();
        UUID member = UUID.fromString(platformValueHolder("unused-" + suffix(), "x"));
        UUID group = groupWithMember(author, member);
        denyOnBy(author, DenySubjectKind.GROUP, group, Permissions.ORG_CREATE);

        asSuper(other);
        assertThatCode(() -> orgContext.runAsPlatform(() -> groupAdmin.update(group,
                new GroupRequest("renamed-" + suffix(), null, null, List.of(member.toString())))))
                .doesNotThrowAnyException();
    }

    /** Undelegating the role takes it from every member at once — the widest version of the same act. */
    @Test
    void aGroupsRoleDelegationCannotBeDroppedByWhoeverCannotLiftItsDeny() {
        String author = superAdmin();
        String other = superAdmin();
        UUID role = privilegedRoleBy(author);
        UUID group = groupDelegating(author, role);
        denyOnBy(author, DenySubjectKind.ROLE, role, Permissions.ORG_CREATE);

        asSuper(other);
        assertThatThrownBy(() -> orgContext.runAsPlatform(() -> groupAdmin.setRoles(group, Set.of())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("user.membership.denyGoverned");
    }

    // --- helpers ---

    private UUID groupWithMember(String admin, UUID memberId) {
        asSuper(admin);
        try {
            UUID id = orgContext.callAsPlatform(() -> UUID.fromString(
                    groups.create(new GroupSpec("rmg-" + suffix(), null, null, Set.of(memberId))).id()));
            createdGroups.add(id);
            return id;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String superAdmin() {
        String s = suffix();
        String username = "rmadmin-" + s;
        UUID id = users.createUser(new NewUser(username, username + "@example.com", "A " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), null).getId();
        createdUsers.add(id);
        orgContext.runAsPlatform(() -> roles.addMember(roles.getOrCreate(Roles.ADMIN).getId(), id));
        return username;
    }

    private String tenantUser(UUID org) {
        String s = suffix();
        String username = "rmt-" + s;
        UUID id = orgContext.callInOrg(org, () -> users.createUser(new NewUser(username, username + "@example.com",
                "T " + s, "S3cret!pw9", Set.of("ROLE_USER")), org).getId());
        createdUsers.add(id);
        return username;
    }

    /** A real user in the org, already carrying the value the rule reads — so a removal has something to delete. */
    private String valueHolder(UUID org, String key, String value) {
        String s = suffix();
        String username = "rmv-" + s;
        UUID id = orgContext.callInOrg(org, () -> users.createUser(new NewUser(username, username + "@example.com",
                "V " + s, "S3cret!pw9", Set.of("ROLE_USER")), org).getId());
        createdUsers.add(id);
        orgContext.runAsPlatform(() -> ownerJdbc().update(
                "insert into entity_attribute (id, entity_kind, entity_id, attr_key, attr_value, org_id) "
                        + "values (gen_random_uuid(), 'USER', ?, ?, ?, ?)", id.toString(), key, value, org));
        return id.toString();
    }

    private String platformValueHolder(String key, String value) {
        String s = suffix();
        String username = "rmp-" + s;
        UUID id = users.createUser(new NewUser(username, username + "@example.com", "P " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), null).getId();
        createdUsers.add(id);
        orgContext.runAsPlatform(() -> ownerJdbc().update(
                "insert into entity_attribute (id, entity_kind, entity_id, attr_key, attr_value, org_id) "
                        + "values (gen_random_uuid(), 'USER', ?, ?, ?, null)", id.toString(), key, value));
        return id.toString();
    }

    private UUID privilegedRoleBy(String admin) {
        asSuper(admin);
        try {
            UUID id = orgContext.callAsPlatform(() ->
                    roles.create("ROLE_RMT_" + suffix().toUpperCase(), Set.of(Permissions.ORG_CREATE)).getId());
            createdRoles.add(id);
            return id;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UUID groupDelegating(String admin, UUID role) {
        asSuper(admin);
        try {
            UUID id = orgContext.callAsPlatform(() -> {
                GroupSpec spec = new GroupSpec("rmg-" + suffix(), null, null, Set.of());
                UUID groupId = UUID.fromString(groups.create(spec).id());
                groups.setRoles(groupId, Set.of(role));
                return groupId;
            });
            createdGroups.add(id);
            return id;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void ruleForRoleBy(String admin, String key, String value, UUID role) {
        ruleBy(admin, key, value, MappingTargetKind.ROLE, role);
    }

    private void ruleForGroupBy(String admin, String key, String value, UUID group) {
        ruleBy(admin, key, value, MappingTargetKind.GROUP, group);
    }

    private void ruleBy(String admin, String key, String value, MappingTargetKind kind, UUID target) {
        asSuper(admin);
        try {
            orgContext.runAsPlatform(() -> mappingRules.create(
                    MappingRuleSpec.single(key, AttributeOperator.EQUALS, value, kind, target)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void denyOnBy(String admin, DenySubjectKind kind, UUID subjectId, String pattern) {
        asSuper(admin);
        try {
            orgContext.runAsPlatform(() -> denies.create(new DenySpec(kind, subjectId, pattern)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void asSuper(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(Roles.ADMIN))));
    }

    private void asTenantAdmin(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(Permissions.USER_UPDATE))));
    }

    private UUID org() {
        String slug = "rmd-" + suffix();
        UUID id = organizations.create(new NewOrganization(slug, slug)).id();
        createdOrgs.add(id);
        return id;
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
