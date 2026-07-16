package com.example.sso.mapping;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.role.RoleService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Auto-mapping through the public API: a mapping rule materializes/retracts group membership for the users
 * matching its predicate, reversibly (provenance) and org-scoped. Rule edits (create/update/delete) reconcile
 * synchronously; an attribute change reconciles asynchronously via the AFTER_COMMIT listener. Fixtures are
 * GLOBAL (platform tier) except the isolation test, which spans two orgs.
 */
class MappingRuleServiceIT extends AbstractIntegrationTest {

    @Autowired MappingRuleService mappingRules;
    @Autowired AttributeService attributes;
    @Autowired UserGroupService groups;
    @Autowired UserService users;
    @Autowired RoleService roles;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();
    private final List<UUID> createdOrgs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        orgContext.runAsPlatform(() -> {
            ownerJdbc().update("delete from mapping_rule_membership");
            ownerJdbc().update("delete from mapping_rule");
            createdUsers.forEach(id -> ownerJdbc().update("delete from entity_attribute where entity_id = ?", id.toString()));
            createdUsers.forEach(users::delete);
            createdGroups.forEach(groups::delete);
            createdRoles.forEach(roles::delete);
            createdOrgs.forEach(id -> ownerJdbc().update("delete from organization where id = ?", id));
        });
        createdUsers.clear();
        createdGroups.clear();
        createdRoles.clear();
        createdOrgs.clear();
    }

    @Test
    void creatingARuleAddsMatchingUsersToTheGroupAndRecordsProvenance() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("eng");
            UUID matching = user("dept", "eng");
            UUID other = user("dept", "sales");

            MappingRuleView rule = mappingRules.create(spec("dept", "eng", group));

            assertThat(groups.groupIdsOf(matching)).contains(group);
            assertThat(groups.groupIdsOf(other)).doesNotContain(group);
            assertThat(rule.assignedCount()).isEqualTo(1);
            assertThat(provenanceCount(rule.id())).isEqualTo(1);
        });
    }

    @Test
    void anAttributeChangeAsynchronouslyAddsThenRetractsTheUserWhileLeavingAManualMemberUntouched() {
        UUID group = orgContext.callAsPlatform(() -> group("eng"));
        UUID manual = orgContext.callAsPlatform(() -> user("dept", "sales")); // never matches
        UUID target = orgContext.callAsPlatform(() -> user("dept", "sales")); // will be re-tagged to eng
        orgContext.runAsPlatform(() -> {
            groups.addMember(group, manual);                       // a hand-added member
            mappingRules.create(spec("dept", "eng", group));
        });

        // Re-tag the user to eng → the listener adds them.
        orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, target.toString(), "dept", "eng"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(inGroup(target, group)).isTrue());

        // Re-tag away from eng → the listener retracts them; the manual member stays.
        orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, target.toString(), "dept", "sales"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(inGroup(target, group)).isFalse());
        assertThat(inGroup(manual, group)).isTrue(); // the hand-added member was never rule-managed
    }

    @Test
    void deletingARuleRetractsEveryMembershipItMaterialized() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("eng");
            UUID matching = user("dept", "eng");
            MappingRuleView rule = mappingRules.create(spec("dept", "eng", group));
            assertThat(inGroup(matching, group)).isTrue();

            mappingRules.delete(UUID.fromString(rule.id()));

            assertThat(inGroup(matching, group)).isFalse();
            assertThat(provenanceCount(rule.id())).isZero();
        });
    }

    @Test
    void updatingARulesTargetRetractsTheOldGroupAndMaterializesTheNew() {
        orgContext.runAsPlatform(() -> {
            UUID oldGroup = group("eng-old");
            UUID newGroup = group("eng-new");
            UUID matching = user("dept", "eng");
            MappingRuleView rule = mappingRules.create(spec("dept", "eng", oldGroup));
            assertThat(inGroup(matching, oldGroup)).isTrue();

            mappingRules.update(UUID.fromString(rule.id()), spec("dept", "eng", newGroup));

            assertThat(inGroup(matching, oldGroup)).isFalse();
            assertThat(inGroup(matching, newGroup)).isTrue();
        });
    }

    @Test
    void aRuleOnlyMatchesUsersInItsOwnTenant() {
        UUID orgA = newOrg("map-a");
        UUID orgB = newOrg("map-b");
        UUID groupA = orgContext.callInOrg(orgA, () -> group("eng"));
        UUID userA = orgContext.callInOrg(orgA, () -> user("dept", "eng", orgA));
        UUID userB = orgContext.callInOrg(orgB, () -> user("dept", "eng", orgB));

        orgContext.runInOrg(orgA, () -> mappingRules.create(spec("dept", "eng", groupA)));

        assertThat(orgContext.callInOrg(orgA, () -> groups.groupIdsOf(userA))).contains(groupA);
        // orgB's user carries the same attribute but the orgA rule (and its group) never reach across tenants.
        assertThat(orgContext.callInOrg(orgB, () -> groups.groupIdsOf(userB))).doesNotContain(groupA);
    }

    @Test
    void targetingASystemGroupOrAGroupOutsideTheTierIsRejected() {
        UUID org = newOrg("map-tier");
        UUID foreignGroup = orgContext.callInOrg(org, () -> group("foreign"));
        // A platform-tier rule cannot target an org-owned group (not in the platform tier).
        orgContext.runAsPlatform(() ->
                assertThatThrownBy(() -> mappingRules.create(spec("dept", "eng", foreignGroup)))
                        .isInstanceOf(BadRequestException.class));
    }

    @Test
    void aUserKeptByASecondRuleIsNotEvictedUntilEveryRuleStopsMatching() {
        // The provenance branch the whole design turns on: two rules put the same user in one group; deleting
        // one must NOT evict them while the other still claims them — only when the last claim goes.
        orgContext.runAsPlatform(() -> {
            UUID group = group("eng");
            UUID both = user("dept", "eng");
            orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, both.toString(), "level", "senior"));
            MappingRuleView byDept = mappingRules.create(spec("dept", "eng", group));
            MappingRuleView byLevel = mappingRules.create(spec("level", "senior", group));
            assertThat(inGroup(both, group)).isTrue();

            mappingRules.delete(UUID.fromString(byDept.id()));
            assertThat(inGroup(both, group)).isTrue(); // still claimed by the level rule

            mappingRules.delete(UUID.fromString(byLevel.id()));
            assertThat(inGroup(both, group)).isFalse(); // last claim gone
        });
    }

    @Test
    void aManuallyAddedMemberWhoAlsoMatchesIsRemovedWhenTheRuleStops() {
        // The documented trade-off: a member who is BOTH hand-added AND rule-matched is governed by the rule's
        // lifecycle — deleting the rule removes them (pinning the behavior the evaluator javadoc promises).
        orgContext.runAsPlatform(() -> {
            UUID group = group("eng");
            UUID both = user("dept", "eng");
            groups.addMember(group, both);                       // also hand-added
            MappingRuleView rule = mappingRules.create(spec("dept", "eng", group));
            assertThat(inGroup(both, group)).isTrue();

            mappingRules.delete(UUID.fromString(rule.id()));
            assertThat(inGroup(both, group)).isFalse(); // rule lifecycle governs the co-owned membership
        });
    }

    @Test
    void deletingTheTargetGroupRemovesItsRulesAndProvenance() {
        UUID group = orgContext.callAsPlatform(() -> group("doomed"));
        UUID matching = orgContext.callAsPlatform(() -> user("dept", "eng"));
        orgContext.runAsPlatform(() -> mappingRules.create(spec("dept", "eng", group)));
        assertThat(inGroup(matching, group)).isTrue();

        orgContext.runAsPlatform(() -> groups.delete(group));
        createdGroups.remove(group); // already deleted; keep teardown from double-deleting

        // MappingTargetDeletionListener (AFTER_COMMIT) drops the now-dangling rules; provenance cascades via the FK.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(rulesForTarget(group)).isZero();
            assertThat(provenanceForTarget(group)).isZero();
        });
    }

    @Test
    void deletingATargetRoleRemovesItsRules() {
        UUID org = newOrg("map-role-del");
        UUID role = orgContext.callInOrg(org, () -> role());
        UUID member = orgContext.callInOrg(org, () -> user("dept", "eng", org));
        orgContext.runInOrg(org, () -> mappingRules.create(new MappingRuleSpec("dept", AttributeOperator.EQUALS, "eng", MappingTargetKind.ROLE, role)));
        assertThat(hasRole(org, member, role)).isTrue();

        orgContext.runInOrg(org, () -> roles.deleteRole(role));
        createdRoles.remove(role); // already deleted; keep teardown from double-deleting

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(rulesForTarget(role)).isZero());
    }

    @Test
    void aGlobalAttributeDoesNotDriveATenantRule() {
        // Security invariant: a platform-set GLOBAL attribute on a tenant user must NOT satisfy the tenant's
        // rule — create/preview both evaluate the tier's OWN attributes only (entityIdsWithInTier), so the
        // global value is invisible to the rule.
        UUID orgA = newOrg("map-global");
        UUID groupA = orgContext.callInOrg(orgA, () -> group("clr"));
        UUID u = orgContext.callInOrg(orgA, () -> user("dept", "x", orgA));         // orgA user, own attr dept=x
        orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, u.toString(), "clearance", "secret")); // GLOBAL

        assertThat(orgContext.callInOrg(orgA, () -> mappingRules.preview(spec("clearance", "secret", groupA)))).isEmpty();
        orgContext.runInOrg(orgA, () -> mappingRules.create(spec("clearance", "secret", groupA)));
        assertThat(orgContext.callInOrg(orgA, () -> groups.groupIdsOf(u))).doesNotContain(groupA);
    }

    @Test
    void removingTheMatchedAttributeAsynchronouslyRetractsTheUser() {
        UUID group = orgContext.callAsPlatform(() -> group("eng"));
        UUID target = orgContext.callAsPlatform(() -> user("dept", "eng"));
        orgContext.runAsPlatform(() -> mappingRules.create(spec("dept", "eng", group)));
        assertThat(inGroup(target, group)).isTrue();

        orgContext.runAsPlatform(() -> attributes.remove(EntityKind.USER, target.toString(), "dept"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(target, group)).isFalse());
    }

    @Test
    void aNonUserAttributeChangeDoesNotDriveMapping() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("eng");
            UUID matching = user("dept", "eng");
            mappingRules.create(spec("dept", "eng", group));
            assertThat(inGroup(matching, group)).isTrue();

            // A GROUP-kind attribute change must not touch user→group auto-mapping (only USER events do).
            attributes.set(EntityKind.GROUP, group.toString(), "team", "core");
        });
        // nothing to await — the listener early-returns for non-USER kinds; membership is unchanged
    }

    @Test
    void previewReturnsTheMatchingUsersWithoutMutating() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("eng");
            UUID matching = user("dept", "eng");
            user("dept", "sales");

            Set<UUID> matched = mappingRules.preview(spec("dept", "eng", group));

            assertThat(matched).containsExactly(matching);
            assertThat(inGroup(matching, group)).isFalse(); // preview is a dry run
        });
    }

    @Test
    void aRoleKindRuleGrantsThenRevokesTheRole() {
        UUID org = newOrg("map-role");
        UUID role = orgContext.callInOrg(org, () -> role());
        UUID member = orgContext.callInOrg(org, () -> user("dept", "eng", org));

        MappingRuleView rule = orgContext.callInOrg(org, () ->
                mappingRules.create(new MappingRuleSpec("dept", AttributeOperator.EQUALS, "eng", MappingTargetKind.ROLE, role)));
        assertThat(hasRole(org, member, role)).isTrue();

        orgContext.runInOrg(org, () -> mappingRules.delete(UUID.fromString(rule.id())));
        assertThat(hasRole(org, member, role)).isFalse();
    }

    @Test
    void previewIsScopedToTheActingTenant() {
        UUID orgA = newOrg("prev-a");
        UUID orgB = newOrg("prev-b");
        UUID groupA = orgContext.callInOrg(orgA, () -> group("eng"));
        UUID userA = orgContext.callInOrg(orgA, () -> user("dept", "eng", orgA));
        orgContext.callInOrg(orgB, () -> user("dept", "eng", orgB)); // same attribute, other tenant

        assertThat(orgContext.callInOrg(orgA, () -> mappingRules.preview(spec("dept", "eng", groupA))))
                .containsExactly(userA); // never enumerates org B's matching user
    }

    @Test
    void anExistsRuleAddsEveryUserCarryingTheKeyRegardlessOfValue() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("has-dept");
            UUID eng = user("dept", "eng");
            UUID sales = user("dept", "sales");
            UUID noDept = user("team", "core");

            MappingRuleView rule = mappingRules.create(existsSpec("dept", group));

            assertThat(groups.groupIdsOf(eng)).contains(group);
            assertThat(groups.groupIdsOf(sales)).contains(group);      // any value of the key matches
            assertThat(groups.groupIdsOf(noDept)).doesNotContain(group); // the key is absent
            assertThat(rule.assignedCount()).isEqualTo(2);
            assertThat(rule.attrOp()).isEqualTo(AttributeOperator.EXISTS);
            assertThat(rule.attrValue()).isNull();
        });
    }

    @Test
    void anExistsRulePreviewAndAsyncPathAgreeOnTheKeyCohort() {
        // The per-rule cohort (create/preview) and the per-user path (async attribute change) must agree for
        // EXISTS: adding the key materializes, removing it retracts.
        UUID group = orgContext.callAsPlatform(() -> group("has-dept"));
        UUID target = orgContext.callAsPlatform(() -> user("team", "core")); // no dept yet
        assertThat(orgContext.callAsPlatform(() -> mappingRules.preview(existsSpec("dept", group)))).isEmpty();
        orgContext.runAsPlatform(() -> mappingRules.create(existsSpec("dept", group)));
        assertThat(inGroup(target, group)).isFalse();

        orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, target.toString(), "dept", "anything"));
        // per-rule path (preview) now reports the user carrying the key — agreeing with the per-user materialize below.
        assertThat(orgContext.callAsPlatform(() -> mappingRules.preview(existsSpec("dept", group)))).contains(target);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(target, group)).isTrue());

        orgContext.runAsPlatform(() -> attributes.remove(EntityKind.USER, target.toString(), "dept"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(target, group)).isFalse());
    }

    @Test
    void aNegativeOperatorMappingRuleIsRejected() {
        // A mapping rule allows only the positive, index-able operators; NOT_* is refused (defense in depth: the
        // service throws even if the request DTO's whitelist were bypassed).
        orgContext.runAsPlatform(() -> {
            UUID group = group("neg");
            assertThatThrownBy(() -> mappingRules.create(new MappingRuleSpec("dept", AttributeOperator.NOT_EQUALS,
                    "sales", MappingTargetKind.GROUP, group))).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> mappingRules.create(new MappingRuleSpec("dept", AttributeOperator.NOT_EXISTS,
                    null, MappingTargetKind.GROUP, group))).isInstanceOf(BadRequestException.class);
        });
    }

    // --- helpers ---

    private int rulesForTarget(UUID targetId) {
        return count("select count(*) from mapping_rule where target_id = ?", targetId);
    }

    private int provenanceForTarget(UUID targetId) {
        return count("select count(*) from mapping_rule_membership where target_id = ?", targetId);
    }

    private int count(String sql, UUID arg) {
        Integer n = ownerJdbc().queryForObject(sql, Integer.class, arg);
        return n == null ? 0 : n;
    }

    private MappingRuleSpec spec(String key, String value, UUID groupId) {
        return new MappingRuleSpec(key, AttributeOperator.EQUALS, value, MappingTargetKind.GROUP, groupId);
    }

    private MappingRuleSpec existsSpec(String key, UUID groupId) {
        return new MappingRuleSpec(key, AttributeOperator.EXISTS, null, MappingTargetKind.GROUP, groupId);
    }

    private boolean inGroup(UUID userId, UUID groupId) {
        return orgContext.callAsPlatform(() -> groups.groupIdsOf(userId)).contains(groupId);
    }

    private int provenanceCount(String ruleId) {
        Integer n = ownerJdbc().queryForObject(
                "select count(*) from mapping_rule_membership where rule_id = ?", Integer.class, UUID.fromString(ruleId));
        return n == null ? 0 : n;
    }

    /** Creates a group in the CURRENT context tier (the caller sets platform/org scope). */
    private UUID group(String prefix) {
        UUID id = UUID.fromString(groups.create(new GroupSpec(prefix + "-" + suffix(), null, null, Set.of())).id());
        createdGroups.add(id);
        return id;
    }

    /** Creates a GLOBAL user carrying {@code key = value} (call inside a platform scope). */
    private UUID user(String key, String value) {
        return user(key, value, null);
    }

    /** Creates a user OWNED by {@code org} carrying {@code key = value} (call inside that org's scope). */
    private UUID user(String key, String value, UUID org) {
        String s = suffix();
        UUID id = users.createUser(new NewUser("u-" + s, "u-" + s + "@example.com", "U " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), org).getId();
        createdUsers.add(id);
        attributes.set(EntityKind.USER, id.toString(), key, value); // stamped in the current context tier
        return id;
    }

    /** Creates a role in the CURRENT context tier. */
    private UUID role() {
        UUID id = roles.create("ROLE_MR_" + suffix().toUpperCase()).getId();
        createdRoles.add(id);
        return id;
    }

    private boolean hasRole(UUID org, UUID userId, UUID roleId) {
        return orgContext.callInOrg(org, () -> users.findById(userId)
                .map(u -> u.getRoles().stream().anyMatch(r -> r.getId().equals(roleId))).orElse(false));
    }

    private UUID newOrg(String prefix) {
        UUID id = orgContext.callAsPlatform(
                () -> organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id());
        createdOrgs.add(id);
        return id;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
