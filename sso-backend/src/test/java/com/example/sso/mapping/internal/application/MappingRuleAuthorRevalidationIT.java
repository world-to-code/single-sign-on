package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.MappingRuleService;
import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingRuleView;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceMemberRow;
import com.example.sso.resource.internal.domain.ResourceMemberRowRepository;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMember;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security fix for the SECOND authority timepoint: a rule's author is re-validated at async materialize time,
 * not just at create. A rule authored while the actor held the authority must STOP handing out grants once that
 * author is demoted or deleted (zero-trust — authority is not frozen at authoring). A rule with no recorded
 * author (legacy/system) is allowed but audited. White-box: drives the internal evaluator directly.
 */
@RecordApplicationEvents
class MappingRuleAuthorRevalidationIT extends AbstractIntegrationTest {

    @Autowired MappingRuleService mappingRules;
    @Autowired MappingRuleEvaluator evaluator;
    @Autowired MappingReconcileSweeper sweeper;
    @Autowired RoleService roles;
    @Autowired UserGroupService groups;
    @Autowired UserService users;
    @Autowired OrgContext orgContext;
    @Autowired ApplicationEvents events;
    @Autowired ResourceRepository resources;
    @Autowired ResourceTypeRepository types;
    @Autowired ResourceTypeAllowedMemberRepository allowedMembers;
    @Autowired ResourceMemberRowRepository resourceMembers;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        orgContext.runAsPlatform(() -> {
            ownerJdbc().update("delete from mapping_rule_membership");
            ownerJdbc().update("delete from mapping_rule");
            resourceMembers.deleteAll();
            resources.deleteAll();
            types.deleteAll();
            createdUsers.forEach(id -> ownerJdbc().update("delete from entity_attribute where entity_id = ?", id.toString()));
            createdUsers.forEach(users::delete);
            createdGroups.forEach(groups::delete);
            createdRoles.forEach(roles::deleteRole);
        });
        createdUsers.clear();
        createdRoles.clear();
        createdGroups.clear();
    }

    @Test
    void aStillAuthorizedAuthorGrantsANewlyMatchingUser() {
        Author author = platform(this::superAuthor);
        UUID role = targetRole(author);
        createRoleRuleAs(author.username(), "dept", "eng", role);

        UUID user = platform(this::plainUser);
        platform(() -> tagUser(user, "dept", "eng"));
        platform(() -> evaluator.reevaluateUser(user));

        assertThat(hasRole(user, role)).isTrue();
    }

    @Test
    void aDeletedAuthorNoLongerGrantsAndIsAudited() {
        Author author = platform(this::superAuthor);
        UUID role = targetRole(author);
        createRoleRuleAs(author.username(), "dept", "eng", role);
        platform(() -> users.delete(author.id()));
        createdUsers.remove(author.id());

        UUID user = platform(this::plainUser);
        platform(() -> tagUser(user, "dept", "eng"));
        long before = auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", role);
        platform(() -> evaluator.reevaluateUser(user));

        assertThat(hasRole(user, role)).isFalse(); // fail-closed: a deleted author resolves to no authority
        assertThat(auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", role)).isGreaterThan(before);
    }

    @Test
    void aDemotedAuthorNoLongerGrantsAndIsAudited() {
        Author author = platform(this::superAuthor);
        UUID role = targetRole(author);
        createRoleRuleAs(author.username(), "dept", "eng", role);
        platform(() -> roles.removeMember(adminRoleId(), author.id())); // strip ROLE_ADMIN — no longer a super

        UUID user = platform(this::plainUser);
        platform(() -> tagUser(user, "dept", "eng"));
        long before = auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", role);
        platform(() -> evaluator.reevaluateUser(user));

        assertThat(hasRole(user, role)).isFalse();
        assertThat(auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", role)).isGreaterThan(before);
    }

    @Test
    void aLegacyRuleWithNoAuthorStillGrantsButIsAudited() {
        Author seed = platform(this::superAuthor); // just to mint the target role; the rule itself has no author
        UUID role = targetRole(seed);
        platform(() -> insertLegacyRule("dept", "eng", role)); // created_by NULL, as a pre-V97 row would be
        UUID user = platform(this::plainUser);
        platform(() -> tagUser(user, "dept", "eng"));
        long before = auditCount("MAPPING_RULE_LEGACY_AUTHOR", role);

        platform(() -> evaluator.reevaluateUser(user));

        assertThat(hasRole(user, role)).isTrue(); // allowed (user-approved policy): no legacy breakage
        assertThat(auditCount("MAPPING_RULE_LEGACY_AUTHOR", role)).isGreaterThan(before); // but surfaced for backfill
    }

    @Test
    void anUpdateReStampsTheAuthorToTheUpdater() {
        Author first = platform(this::superAuthor);
        Author second = platform(this::superAuthor);
        UUID role = targetRole(first);
        UUID ruleId = UUID.fromString(createRoleRuleAs(first.username(), "dept", "eng", role).id());
        assertThat(createdByOf(ruleId)).isEqualTo(first.id());

        setAuth(second.username());
        try {
            orgContext.runAsPlatform(() ->
                    mappingRules.update(ruleId,
                            new MappingRuleSpec("dept", AttributeOperator.EQUALS, "eng", MappingTargetKind.ROLE, role)));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(createdByOf(ruleId)).isEqualTo(second.id()); // the updater vouches now, not the original author
    }

    @Test
    void aDemotedAuthorGrantsNoCohortViaTheSweepAndIsAudited() {
        // The COHORT path (materializeAll, driven by the scheduled sweep's reevaluateRule) must apply the same
        // author re-check as the single-user path — this is the higher-blast-radius, continuously-running path.
        Author author = platform(this::superAuthor);
        UUID role = targetRole(author);
        createRoleRuleAs(author.username(), "dept", "eng", role); // no matching users yet
        platform(() -> roles.removeMember(adminRoleId(), author.id())); // demote

        UUID u1 = platform(this::plainUser);
        UUID u2 = platform(this::plainUser);
        platform(() -> {
            tagUser(u1, "dept", "eng");
            tagUser(u2, "dept", "eng");
        });
        long before = auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", role);

        platform(() -> sweeper.reconcileAllTiers()); // cohort path: reevaluateRule -> materializeAll

        assertThat(hasRole(u1, role)).isFalse();
        assertThat(hasRole(u2, role)).isFalse();
        assertThat(auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", role)).isGreaterThan(before);
    }

    @Test
    void aDemotedAuthorGrantsNoGroupMembershipAndIsAudited() {
        // GROUP-target re-validation end-to-end (canAccessGroup off the request thread): a super author manages
        // any group (unscoped); once demoted they no longer do, so the group rule stops materializing.
        Author author = platform(this::superAuthor);
        UUID group = platform(this::targetGroup);
        createRuleAs(author.username(), "dept", "eng", MappingTargetKind.GROUP, group); // no matching users yet
        platform(() -> roles.removeMember(adminRoleId(), author.id())); // demote → no longer unscoped

        UUID user = platform(this::plainUser);
        platform(() -> tagUser(user, "dept", "eng"));
        long before = auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", group);

        platform(() -> evaluator.reevaluateUser(user));

        assertThat(inGroup(user, group)).isFalse();
        assertThat(auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", group)).isGreaterThan(before);
    }

    @Test
    void aDemotedAuthorAddsNoResourceMembershipAndIsAudited() {
        // RESOURCE_MEMBER re-validation (canManage off the request thread): a super manages any resource; once
        // demoted (no ADMIN grants) they manage none, so the resource rule stops materializing.
        Author author = platform(this::superAuthor);
        UUID resource = platform(this::targetResource);
        createRuleAs(author.username(), "dept", "eng", MappingTargetKind.RESOURCE_MEMBER, resource);
        platform(() -> roles.removeMember(adminRoleId(), author.id())); // demote → no longer unscoped

        UUID user = platform(this::plainUser);
        platform(() -> tagUser(user, "dept", "eng"));
        long before = auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", resource);

        platform(() -> evaluator.reevaluateUser(user));

        assertThat(isResourceMember(resource, user)).isFalse();
        assertThat(auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", resource)).isGreaterThan(before);
    }

    @Test
    void aDemotedAuthorAddsNoResourceCohortViaTheSweep() {
        Author author = platform(this::superAuthor);
        UUID resource = platform(this::targetResource);
        createRuleAs(author.username(), "dept", "eng", MappingTargetKind.RESOURCE_MEMBER, resource);
        platform(() -> roles.removeMember(adminRoleId(), author.id())); // demote

        UUID u1 = platform(this::plainUser);
        UUID u2 = platform(this::plainUser);
        platform(() -> {
            tagUser(u1, "dept", "eng");
            tagUser(u2, "dept", "eng");
        });
        long before = auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", resource);

        platform(() -> sweeper.reconcileAllTiers()); // cohort path: reevaluateRule -> materializeAll

        assertThat(isResourceMember(resource, u1)).isFalse();
        assertThat(isResourceMember(resource, u2)).isFalse();
        assertThat(auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", resource)).isGreaterThan(before);
    }

    @Test
    void aMaterializedGrantAndItsRetractEachFireTheSessionRevocationFanout() {
        // The reorder grants only when insertClaimIfAbsent inserts; assert the access-change fan-out (which drives
        // session termination / token revocation) still fires exactly on the real grant AND on the retract, not
        // just that the terminal membership state is right.
        Author author = platform(this::superAuthor);
        UUID role = targetRole(author);
        createRoleRuleAs(author.username(), "dept", "eng", role);
        UUID user = platform(this::plainUser);
        String username = usernameOf(user);
        platform(() -> tagUser(user, "dept", "eng"));

        long beforeGrant = accessEventsFor(username);
        platform(() -> evaluator.reevaluateUser(user)); // GRANT
        assertThat(hasRole(user, role)).isTrue();
        assertThat(accessEventsFor(username)).isGreaterThan(beforeGrant);

        platform(() -> ownerJdbc().update(
                "delete from entity_attribute where entity_id = ? and attr_key = 'dept'", user.toString()));
        long beforeRetract = accessEventsFor(username);
        platform(() -> evaluator.reevaluateUser(user)); // RETRACT (never gated by the author check)
        assertThat(hasRole(user, role)).isFalse();
        assertThat(accessEventsFor(username)).isGreaterThan(beforeRetract);
    }

    // --- helpers ---

    private record Author(UUID id, String username) {
    }

    private MappingRuleView createRoleRuleAs(String username, String key, String value, UUID targetRole) {
        return createRuleAs(username, key, value, MappingTargetKind.ROLE, targetRole);
    }

    private MappingRuleView createRuleAs(String username, String key, String value, MappingTargetKind kind, UUID target) {
        setAuth(username);
        try {
            return orgContext.callAsPlatform(
                    () -> mappingRules.create(new MappingRuleSpec(key, AttributeOperator.EQUALS, value, kind, target)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void setAuth(String username) {
        // A platform super-admin identity: resolveAuthor's platform branch resolves the GLOBAL account by username.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    /** A global super-admin (ROLE_ADMIN) whose id is recorded as the rule's author. */
    private Author superAuthor() {
        String s = suffix();
        String username = "admin-" + s;
        UUID id = users.createUser(new NewUser(username, username + "@example.com", "A " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), null).getId();
        createdUsers.add(id);
        roles.addMember(adminRoleId(), id);
        return new Author(id, username);
    }

    private UUID adminRoleId() {
        return roles.getOrCreate("ROLE_ADMIN").getId();
    }

    /**
     * A global custom role that carries a PLATFORM permission, so ONLY a super may assign it — this is what makes
     * a demoted/deleted author (no longer a super) fail the re-check. Created in the (super) author's context so
     * the grant policy permits the platform permission.
     */
    private UUID targetRole(Author author) {
        setAuth(author.username());
        try {
            UUID id = orgContext.callAsPlatform(() ->
                    roles.create("ROLE_TGT_" + suffix().toUpperCase(), Set.of(Permissions.ORG_CREATE)).getId());
            createdRoles.add(id);
            return id;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UUID plainUser() {
        String s = suffix();
        UUID id = users.createUser(new NewUser("u-" + s, "u-" + s + "@example.com", "U " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), null).getId();
        createdUsers.add(id);
        return id;
    }

    private void tagUser(UUID userId, String key, String value) {
        ownerJdbc().update(
                "insert into entity_attribute (entity_kind, entity_id, attr_key, attr_value, org_id) values (?,?,?,?,null)",
                "USER", userId.toString(), key, value);
    }

    private void insertLegacyRule(String key, String value, UUID targetRole) {
        // A pre-V97 row has no author; after V100 it also carries attr_op = 'EQUALS' (the backfill default), so a
        // faithful legacy fixture stamps EQUALS explicitly — the column is NOT NULL with no server default.
        ownerJdbc().update(
                "insert into mapping_rule (attr_key, attr_op, attr_value, then_kind, target_id, org_id, created_by) "
                        + "values (?, 'EQUALS', ?, 'ROLE', ?, null, null)",
                key, value, targetRole);
    }

    private boolean hasRole(UUID userId, UUID roleId) {
        return orgContext.callAsPlatform(() -> users.findById(userId)
                .map(u -> u.getRoles().stream().anyMatch(r -> r.getId().equals(roleId))).orElse(false));
    }

    /** A global group a rule can target. */
    private UUID targetGroup() {
        UUID id = UUID.fromString(groups.create(new GroupSpec("g-" + suffix(), null, null, Set.of())).id());
        createdGroups.add(id);
        return id;
    }

    private boolean inGroup(UUID userId, UUID groupId) {
        return orgContext.callAsPlatform(() -> groups.groupIdsOf(userId)).contains(groupId);
    }

    /** A global resource whose type allows USER members, a rule can target. */
    private UUID targetResource() {
        ResourceType type = types.save(new ResourceType("T-" + suffix(), null));
        allowedMembers.save(new ResourceTypeAllowedMember(type.getId(), MemberType.USER, null));
        return resources.save(new Resource("R-" + suffix(), type, null)).getId();
    }

    private boolean isResourceMember(UUID resourceId, UUID userId) {
        return orgContext.callAsPlatform(() -> resourceMembers.findByResourceId(resourceId).stream()
                .anyMatch(row -> row.getMemberId().equals(userId.toString())));
    }

    private String usernameOf(UUID userId) {
        return orgContext.callAsPlatform(() -> users.findById(userId).map(UserAccount::getUsername).orElseThrow());
    }

    /** How many access-change (session-revocation) events have fired for {@code username} so far this test. */
    private long accessEventsFor(String username) {
        return events.stream(UserAccessChangedEvent.class).filter(e -> username.equals(e.username())).count();
    }

    private UUID createdByOf(UUID ruleId) {
        return orgContext.callAsPlatform(() ->
                ownerJdbc().queryForObject("select created_by from mapping_rule where id = ?", UUID.class, ruleId));
    }

    private long auditCount(String type, UUID targetId) {
        Long n = orgContext.callAsPlatform(() -> ownerJdbc().queryForObject(
                "select count(*) from audit_event where type = ? and detail like ?",
                Long.class, type, "%" + targetId + "%"));
        return n == null ? 0 : n;
    }

    private <T> T platform(Supplier<T> action) {
        return orgContext.callAsPlatform(action);
    }

    private void platform(Runnable action) {
        orgContext.runAsPlatform(action);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
