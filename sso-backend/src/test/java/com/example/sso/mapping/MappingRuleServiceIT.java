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
            createdGroups.forEach(id -> ownerJdbc().update("delete from entity_attribute where entity_id = ?", id.toString()));
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
    void aRuleMatchesAUserOnAnyOfItsMultipleValuesForAKeyAndMaterializesOnce() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("infra");
            UUID member = user("team", "infra");
            attributes.add(EntityKind.USER, member.toString(), "team", "sre"); // team is now multi-value {infra, sre}
            UUID other = user("team", "ops");

            // A rule keyed on the SECOND value still materializes the member (ANY-match), and exactly once (dedup).
            MappingRuleView rule = mappingRules.create(spec("team", "sre", group));

            assertThat(groups.groupIdsOf(member)).contains(group);
            assertThat(groups.groupIdsOf(other)).doesNotContain(group);
            assertThat(rule.assignedCount()).isEqualTo(1); // materialized once, not once per value
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
        orgContext.runInOrg(org, () -> mappingRules.create(MappingRuleSpec.single("dept", AttributeOperator.EQUALS, "eng", MappingTargetKind.ROLE, role)));
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
                mappingRules.create(MappingRuleSpec.single("dept", AttributeOperator.EQUALS, "eng", MappingTargetKind.ROLE, role)));
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
            assertThat(rule.conditions()).singleElement()
                    .satisfies(c -> assertThat(c.attrOp()).isEqualTo(AttributeOperator.EXISTS))
                    .satisfies(c -> assertThat(c.attrValue()).isNull());
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
            assertThatThrownBy(() -> mappingRules.create(MappingRuleSpec.single("dept", AttributeOperator.NOT_EQUALS,
                    "sales", MappingTargetKind.GROUP, group))).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> mappingRules.create(MappingRuleSpec.single("dept", AttributeOperator.NOT_EXISTS,
                    null, MappingTargetKind.GROUP, group))).isInstanceOf(BadRequestException.class);
        });
    }

    @Test
    void aCompoundRuleAssignsOnlyUsersSatisfyingEveryCondition() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("senior-eng");
            UUID both = user("dept", "eng");                                            // dept=eng ...
            attributes.set(EntityKind.USER, both.toString(), "level", "senior");        // ... AND level=senior
            UUID onlyDept = user("dept", "eng");
            attributes.set(EntityKind.USER, onlyDept.toString(), "level", "junior");    // level mismatch
            UUID onlyLevel = user("dept", "sales");
            attributes.set(EntityKind.USER, onlyLevel.toString(), "level", "senior");   // dept mismatch

            MappingRuleView rule = mappingRules.create(new MappingRuleSpec(List.of(
                    new MappingCondition("dept", AttributeOperator.EQUALS, "eng"),
                    new MappingCondition("level", AttributeOperator.EQUALS, "senior")),
                    MappingTargetKind.GROUP, group));

            assertThat(inGroup(both, group)).isTrue();                 // satisfies every condition
            assertThat(inGroup(onlyDept, group)).isFalse();            // fails the level condition
            assertThat(inGroup(onlyLevel, group)).isFalse();          // fails the dept condition
            assertThat(rule.assignedCount()).isEqualTo(1);
            assertThat(rule.conditions()).hasSize(2);
        });
    }

    @Test
    void aCompoundRuleMixesExistsAndEqualsConditions() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("has-dept-senior");
            UUID match = user("dept", "anything");                                      // dept EXISTS ...
            attributes.set(EntityKind.USER, match.toString(), "level", "senior");       // ... AND level=senior
            UUID noDept = user("level", "senior");                                      // senior but no dept

            mappingRules.create(new MappingRuleSpec(List.of(
                    new MappingCondition("dept", AttributeOperator.EXISTS, null),
                    new MappingCondition("level", AttributeOperator.EQUALS, "senior")),
                    MappingTargetKind.GROUP, group));

            assertThat(inGroup(match, group)).isTrue();
            assertThat(inGroup(noDept, group)).isFalse();             // EXISTS dept fails
        });
    }

    @Test
    void aCompoundRuleAsyncPathMaterializesOnlyOnceEveryConditionIsSatisfied() {
        // The per-user (async) path must agree with the intersection cohort: a user matching only SOME conditions
        // is not materialized until the LAST one is satisfied, and is retracted when it is lost again.
        UUID group = orgContext.callAsPlatform(() -> group("senior-eng"));
        UUID target = orgContext.callAsPlatform(() -> user("dept", "eng")); // dept=eng, not yet level=senior
        orgContext.runAsPlatform(() -> mappingRules.create(new MappingRuleSpec(List.of(
                new MappingCondition("dept", AttributeOperator.EQUALS, "eng"),
                new MappingCondition("level", AttributeOperator.EQUALS, "senior")),
                MappingTargetKind.GROUP, group)));
        assertThat(inGroup(target, group)).isFalse(); // only one condition holds

        orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, target.toString(), "level", "senior"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(target, group)).isTrue());

        orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, target.toString(), "level", "junior"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(target, group)).isFalse());
    }

    @Test
    void updatingRuleConditionsReplacesTheSetAndReconcilesTheCohort() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("eng");
            UUID engJunior = user("dept", "eng");
            attributes.set(EntityKind.USER, engJunior.toString(), "level", "junior");
            UUID engSenior = user("dept", "eng");
            attributes.set(EntityKind.USER, engSenior.toString(), "level", "senior");

            // Start with one condition (dept=eng) → both users match.
            MappingRuleView rule = mappingRules.create(spec("dept", "eng", group));
            assertThat(inGroup(engJunior, group)).isTrue();
            assertThat(inGroup(engSenior, group)).isTrue();

            // Tighten to dept=eng AND level=senior → the condition set is REPLACED (not appended); the junior user
            // no longer matches and is retracted, the senior stays.
            mappingRules.update(UUID.fromString(rule.id()), new MappingRuleSpec(List.of(
                    new MappingCondition("dept", AttributeOperator.EQUALS, "eng"),
                    new MappingCondition("level", AttributeOperator.EQUALS, "senior")),
                    MappingTargetKind.GROUP, group));

            assertThat(inGroup(engJunior, group)).isFalse();
            assertThat(inGroup(engSenior, group)).isTrue();
            assertThat(mappingRules.get(UUID.fromString(rule.id())).conditions()).hasSize(2); // replaced, not appended
        });
    }

    @Test
    void anInConditionAssignsUsersCarryingAnyValueInTheList() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("eng-or-infra");
            UUID eng = user("dept", "eng");
            UUID infra = user("dept", "infra");
            UUID sales = user("dept", "sales");

            MappingRuleView rule = mappingRules.create(new MappingRuleSpec(List.of(
                    new MappingCondition("dept", AttributeOperator.IN, null, List.of("eng", "infra"))),
                    MappingTargetKind.GROUP, group));

            assertThat(inGroup(eng, group)).isTrue();
            assertThat(inGroup(infra, group)).isTrue();
            assertThat(inGroup(sales, group)).isFalse();          // outside the list
            assertThat(rule.assignedCount()).isEqualTo(2);
            assertThat(rule.conditions()).singleElement()
                    .satisfies(c -> assertThat(c.attrOp()).isEqualTo(AttributeOperator.IN))
                    .satisfies(c -> assertThat(c.attrValues()).containsExactly("eng", "infra"));
        });
    }

    @Test
    void anInCohortIsReconciledAsynchronouslyWhenAnAttributeEntersOrLeavesTheList() {
        // The per-user (async) path must agree with the union cohort for IN too: gaining a listed value adds the
        // user, changing to an unlisted value retracts them.
        UUID group = orgContext.callAsPlatform(() -> group("eng-or-infra"));
        UUID target = orgContext.callAsPlatform(() -> user("dept", "sales")); // outside the list
        orgContext.runAsPlatform(() -> mappingRules.create(new MappingRuleSpec(List.of(
                new MappingCondition("dept", AttributeOperator.IN, null, List.of("eng", "infra"))),
                MappingTargetKind.GROUP, group)));
        assertThat(inGroup(target, group)).isFalse();

        orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, target.toString(), "dept", "infra"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(target, group)).isTrue());

        orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, target.toString(), "dept", "legal"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(target, group)).isFalse());
    }

    @Test
    void aCompoundRuleCombinesAnInConditionWithAnEquals() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("senior-eng-or-infra");
            UUID match = user("dept", "infra");                                         // dept IN (eng,infra) ...
            attributes.set(EntityKind.USER, match.toString(), "level", "senior");       // ... AND level=senior
            UUID wrongDept = user("dept", "sales");
            attributes.set(EntityKind.USER, wrongDept.toString(), "level", "senior");   // dept outside the list
            UUID wrongLevel = user("dept", "eng");
            attributes.set(EntityKind.USER, wrongLevel.toString(), "level", "junior");  // level mismatch

            mappingRules.create(new MappingRuleSpec(List.of(
                    new MappingCondition("dept", AttributeOperator.IN, null, List.of("eng", "infra")),
                    new MappingCondition("level", AttributeOperator.EQUALS, "senior")),
                    MappingTargetKind.GROUP, group));

            assertThat(inGroup(match, group)).isTrue();
            assertThat(inGroup(wrongDept, group)).isFalse();
            assertThat(inGroup(wrongLevel, group)).isFalse();
        });
    }

    @Test
    void aContainsConditionAssignsUsersWhoseValueIncludesTheSubstringCaseInsensitively() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("has-eng");
            UUID eng = user("dept", "engineering");    // contains "eng"
            UUID upper = user("dept", "ENG-TEAM");      // contains "eng" case-insensitively
            UUID sales = user("dept", "sales");         // no "eng"

            MappingRuleView rule = mappingRules.create(new MappingRuleSpec(List.of(
                    new MappingCondition("dept", AttributeOperator.CONTAINS, "eng")),
                    MappingTargetKind.GROUP, group));

            assertThat(inGroup(eng, group)).isTrue();
            assertThat(inGroup(upper, group)).isTrue();
            assertThat(inGroup(sales, group)).isFalse();
            assertThat(rule.conditions()).singleElement()
                    .satisfies(c -> assertThat(c.attrOp()).isEqualTo(AttributeOperator.CONTAINS))
                    .satisfies(c -> assertThat(c.attrValue()).isEqualTo("eng"));
        });
    }

    @Test
    void aContainsSubstringWithLikeWildcardsMatchesLiterally() {
        // Security: the substring's % / _ / \ are escaped, so they match literally rather than as LIKE wildcards.
        orgContext.runAsPlatform(() -> {
            UUID pct = group("literal-pct");
            UUID pctLiteral = user("discount", "50%off");        // literally contains "50%"
            UUID pctVictim = user("discount", "50pctoff");       // matches only if % were an unescaped wildcard
            mappingRules.create(new MappingRuleSpec(List.of(
                    new MappingCondition("discount", AttributeOperator.CONTAINS, "50%")),
                    MappingTargetKind.GROUP, pct));
            assertThat(inGroup(pctLiteral, pct)).isTrue();
            assertThat(inGroup(pctVictim, pct)).isFalse();       // % is a literal, not a wildcard

            UUID under = group("literal-underscore");
            UUID underLiteral = user("code", "a_c");             // literally contains "a_c"
            UUID underVictim = user("code", "axc");              // matches only if _ were a single-char wildcard
            mappingRules.create(new MappingRuleSpec(List.of(
                    new MappingCondition("code", AttributeOperator.CONTAINS, "a_c")),
                    MappingTargetKind.GROUP, under));
            assertThat(inGroup(underLiteral, under)).isTrue();
            assertThat(inGroup(underVictim, under)).isFalse();   // _ is a literal, not a wildcard

            UUID slash = group("literal-backslash");
            UUID slashLiteral = user("path", "a\\b");            // value "a\b" — the escape char, matched literally
            mappingRules.create(new MappingRuleSpec(List.of(
                    new MappingCondition("path", AttributeOperator.CONTAINS, "a\\b")),
                    MappingTargetKind.GROUP, slash));
            assertThat(inGroup(slashLiteral, slash)).isTrue();   // \ is escaped, the pattern is not corrupted
        });
    }

    @Test
    void aContainsRuleInATenantMatchesOnlyThatTenantsUsers() {
        // Exercises the ORG-scoped native ILIKE query (org_id = :org) + its tenant isolation: orgB's identically
        // valued user must NOT be pulled into orgA's rule.
        UUID orgA = newOrg("cont-a");
        UUID orgB = newOrg("cont-b");
        UUID groupA = orgContext.callInOrg(orgA, () -> group("eng-a"));
        UUID userA = orgContext.callInOrg(orgA, () -> user("dept", "engineering", orgA));
        UUID userB = orgContext.callInOrg(orgB, () -> user("dept", "engineering", orgB)); // same value, other tenant

        orgContext.runInOrg(orgA, () -> mappingRules.create(new MappingRuleSpec(List.of(
                new MappingCondition("dept", AttributeOperator.CONTAINS, "eng")),
                MappingTargetKind.GROUP, groupA)));

        assertThat(orgContext.callInOrg(orgA, () -> groups.groupIdsOf(userA))).contains(groupA);
        assertThat(orgContext.callInOrg(orgB, () -> groups.groupIdsOf(userB))).doesNotContain(groupA);
    }

    @Test
    void aCompoundRuleCombinesContainsWithEquals() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("eng-substring-senior");
            UUID match = user("dept", "engineering");                                   // dept CONTAINS eng ...
            attributes.set(EntityKind.USER, match.toString(), "level", "senior");        // ... AND level=senior
            UUID wrongDept = user("dept", "sales");
            attributes.set(EntityKind.USER, wrongDept.toString(), "level", "senior");    // no "eng"
            UUID wrongLevel = user("dept", "engineering");
            attributes.set(EntityKind.USER, wrongLevel.toString(), "level", "junior");   // level mismatch

            mappingRules.create(new MappingRuleSpec(List.of(
                    new MappingCondition("dept", AttributeOperator.CONTAINS, "eng"),
                    new MappingCondition("level", AttributeOperator.EQUALS, "senior")),
                    MappingTargetKind.GROUP, group));

            assertThat(inGroup(match, group)).isTrue();
            assertThat(inGroup(wrongDept, group)).isFalse();
            assertThat(inGroup(wrongLevel, group)).isFalse();
        });
    }

    @Test
    void aContainsCohortIsReconciledAsynchronouslyOnAnAttributeChange() {
        // The per-user (matches) and per-rule (ILIKE) CONTAINS paths must agree: gaining a value that includes the
        // substring adds the user, changing to one that does not retracts them.
        UUID group = orgContext.callAsPlatform(() -> group("has-eng"));
        UUID target = orgContext.callAsPlatform(() -> user("dept", "sales")); // no "eng"
        orgContext.runAsPlatform(() -> mappingRules.create(new MappingRuleSpec(List.of(
                new MappingCondition("dept", AttributeOperator.CONTAINS, "eng")),
                MappingTargetKind.GROUP, group)));
        assertThat(inGroup(target, group)).isFalse();

        orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, target.toString(), "dept", "engineering"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(target, group)).isTrue());

        orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, target.toString(), "dept", "finance"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(target, group)).isFalse());
    }

    @Test
    void aRuleMatchesUsersWhoInheritTheAttributeFromAGroup() {
        // Inheritance at create: a user with NO own dept but a member of a group tagged dept=eng matches a
        // dept=eng rule; a non-member does not.
        orgContext.runAsPlatform(() -> {
            UUID target = group("target");
            UUID engGroup = group("eng-dept");
            attributes.set(EntityKind.GROUP, engGroup.toString(), "dept", "eng");
            UUID member = plainUser();
            groups.addMember(engGroup, member);
            UUID nonMember = plainUser();

            mappingRules.create(spec("dept", "eng", target));

            assertThat(inGroup(member, target)).isTrue();     // inherited via engGroup
            assertThat(inGroup(nonMember, target)).isFalse();
        });
    }

    @Test
    void taggingAGroupAsynchronouslyMaterializesItsMembers() {
        // A GROUP attribute change re-evaluates the group's members (they inherit the new tag).
        UUID target = orgContext.callAsPlatform(() -> group("target"));
        UUID engGroup = orgContext.callAsPlatform(() -> group("eng-dept"));
        UUID member = orgContext.callAsPlatform(this::plainUser);
        orgContext.runAsPlatform(() -> {
            groups.addMember(engGroup, member);
            mappingRules.create(spec("dept", "eng", target));
        });
        assertThat(inGroup(member, target)).isFalse(); // engGroup not tagged yet

        orgContext.runAsPlatform(() -> attributes.set(EntityKind.GROUP, engGroup.toString(), "dept", "eng"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(member, target)).isTrue());
    }

    @Test
    void addingOrRemovingAUserFromATaggedGroupAsynchronouslyReconcilesThem() {
        // A membership change re-evaluates the user: joining a tagged group materializes them, leaving retracts.
        UUID target = orgContext.callAsPlatform(() -> group("target"));
        UUID engGroup = orgContext.callAsPlatform(() -> group("eng-dept"));
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.GROUP, engGroup.toString(), "dept", "eng");
            mappingRules.create(spec("dept", "eng", target));
        });
        UUID user = orgContext.callAsPlatform(this::plainUser);
        assertThat(inGroup(user, target)).isFalse();

        orgContext.runAsPlatform(() -> groups.addMember(engGroup, user));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(user, target)).isTrue());

        orgContext.runAsPlatform(() -> groups.removeMember(engGroup, user));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(user, target)).isFalse());
    }

    @Test
    void aCompoundRuleCanSatisfyOneConditionViaOwnAttributeAndAnotherViaGroupInheritance() {
        // Inheritance composes with AND (union of own + group attrs): dept=eng comes from the group, level=senior
        // from the user's own attribute — together they satisfy the compound rule.
        orgContext.runAsPlatform(() -> {
            UUID target = group("target");
            UUID engGroup = group("eng-dept");
            attributes.set(EntityKind.GROUP, engGroup.toString(), "dept", "eng"); // inherited condition
            UUID user = user("level", "senior");                                  // own condition
            groups.addMember(engGroup, user);

            mappingRules.create(new MappingRuleSpec(List.of(
                    new MappingCondition("dept", AttributeOperator.EQUALS, "eng"),
                    new MappingCondition("level", AttributeOperator.EQUALS, "senior")),
                    MappingTargetKind.GROUP, target));

            assertThat(inGroup(user, target)).isTrue();
        });
    }

    @Test
    void groupInheritanceForAMappingRuleIsConfinedToTheTenant() {
        // A group tag drives only its own tenant's rule: orgB's member of an identically-tagged group is never
        // pulled into orgA's rule.
        UUID orgA = newOrg("inh-a");
        UUID orgB = newOrg("inh-b");
        UUID targetA = orgContext.callInOrg(orgA, () -> group("target-a"));
        UUID engA = orgContext.callInOrg(orgA, () -> group("eng-a"));
        UUID engB = orgContext.callInOrg(orgB, () -> group("eng-b"));
        UUID memberA = orgContext.callInOrg(orgA, () -> plainUser(orgA));
        UUID memberB = orgContext.callInOrg(orgB, () -> plainUser(orgB));
        orgContext.runInOrg(orgA, () -> {
            attributes.set(EntityKind.GROUP, engA.toString(), "dept", "eng");
            groups.addMember(engA, memberA);
        });
        orgContext.runInOrg(orgB, () -> {
            attributes.set(EntityKind.GROUP, engB.toString(), "dept", "eng");
            groups.addMember(engB, memberB);
        });

        orgContext.runInOrg(orgA, () -> mappingRules.create(spec("dept", "eng", targetA)));

        assertThat(orgContext.callInOrg(orgA, () -> groups.groupIdsOf(memberA))).contains(targetA);
        assertThat(orgContext.callInOrg(orgB, () -> groups.groupIdsOf(memberB))).doesNotContain(targetA);
    }

    @Test
    void groupInheritanceViaTheAsyncPathIsTenantIsolated() {
        // The per-user ASYNC path (membership event → runInOrg → reevaluateUser → effectiveAttributes →
        // unionAttributesOfInTier) must be tier-scoped: a membership change in org A materializes A's member, and
        // org B's member of an identically-tagged group is never pulled into A's rule.
        UUID orgA = newOrg("inh-async-a");
        UUID orgB = newOrg("inh-async-b");
        UUID targetA = orgContext.callInOrg(orgA, () -> group("target-a"));
        UUID engA = orgContext.callInOrg(orgA, () -> group("eng-a"));
        UUID engB = orgContext.callInOrg(orgB, () -> group("eng-b"));
        UUID memberB = orgContext.callInOrg(orgB, () -> plainUser(orgB));
        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.GROUP, engA.toString(), "dept", "eng"));
        orgContext.runInOrg(orgB, () -> {
            attributes.set(EntityKind.GROUP, engB.toString(), "dept", "eng");
            groups.addMember(engB, memberB);
        });
        orgContext.runInOrg(orgA, () -> mappingRules.create(spec("dept", "eng", targetA)));

        UUID memberA = orgContext.callInOrg(orgA, () -> plainUser(orgA));
        orgContext.runInOrg(orgA, () -> groups.addMember(engA, memberA)); // async membership event, in org A's tier

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                orgContext.callInOrg(orgA, () -> groups.groupIdsOf(memberA))).contains(targetA));
        assertThat(orgContext.callInOrg(orgB, () -> groups.groupIdsOf(memberB))).doesNotContain(targetA);
    }

    @Test
    void aGlobalGroupTagDoesNotDriveATenantRuleViaInheritance() {
        // A platform-set GLOBAL tag on a group must not satisfy a TENANT rule via inheritance — the in-tier reads
        // (unionAttributesOfInTier / entityIdsWithInTier on GROUP) never fold in a global tag.
        UUID org = newOrg("inh-global");
        UUID target = orgContext.callInOrg(org, () -> group("target"));
        UUID engGroup = orgContext.callInOrg(org, () -> group("eng"));
        UUID member = orgContext.callInOrg(org, () -> plainUser(org));
        orgContext.runInOrg(org, () -> groups.addMember(engGroup, member));
        orgContext.runAsPlatform(() -> attributes.set(EntityKind.GROUP, engGroup.toString(), "dept", "eng")); // GLOBAL

        orgContext.runInOrg(org, () -> mappingRules.create(spec("dept", "eng", target)));

        assertThat(orgContext.callInOrg(org, () -> groups.groupIdsOf(member))).doesNotContain(target);
    }

    @Test
    void aGroupTagIsInheritedOnTheAsyncPathEvenWhenTheOwnValueDiffers() {
        // effectiveAttributes UNIONs own + group (not shadow): a user whose own dept=sales joins a group tagged
        // dept=eng and is materialized by a dept=eng rule via the async membership path.
        UUID target = orgContext.callAsPlatform(() -> group("target"));
        UUID engGroup = orgContext.callAsPlatform(() -> group("eng-dept"));
        UUID user = orgContext.callAsPlatform(() -> user("dept", "sales")); // own dept=sales, NOT eng
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.GROUP, engGroup.toString(), "dept", "eng");
            mappingRules.create(spec("dept", "eng", target));
        });
        assertThat(inGroup(user, target)).isFalse(); // own sales, not yet in engGroup

        orgContext.runAsPlatform(() -> groups.addMember(engGroup, user)); // async → reevaluateUser
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inGroup(user, target)).isTrue());
    }

    @Test
    void anExistsConditionMatchesViaGroupInheritance() {
        // The GROUP branch of the cohort works for the non-EQUALS operators too (here EXISTS).
        orgContext.runAsPlatform(() -> {
            UUID target = group("has-dept");
            UUID engGroup = group("eng-dept");
            attributes.set(EntityKind.GROUP, engGroup.toString(), "dept", "anything");
            UUID member = plainUser();
            groups.addMember(engGroup, member);

            mappingRules.create(existsSpec("dept", target));

            assertThat(inGroup(member, target)).isTrue(); // inherits the key from the group
        });
    }

    @Test
    void aRuleNeedsAtLeastOneCondition() {
        orgContext.runAsPlatform(() -> {
            UUID group = group("empty");
            assertThatThrownBy(() -> mappingRules.create(
                    new MappingRuleSpec(List.of(), MappingTargetKind.GROUP, group)))
                    .isInstanceOf(BadRequestException.class);
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
        return MappingRuleSpec.single(key, AttributeOperator.EQUALS, value, MappingTargetKind.GROUP, groupId);
    }

    private MappingRuleSpec existsSpec(String key, UUID groupId) {
        return MappingRuleSpec.single(key, AttributeOperator.EXISTS, null, MappingTargetKind.GROUP, groupId);
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

    /** A user with NO attributes of their own, in the current tier — for proving GROUP-inherited matching. */
    private UUID plainUser() {
        return plainUser(null);
    }

    private UUID plainUser(UUID org) {
        String s = suffix();
        UUID id = users.createUser(new NewUser("u-" + s, "u-" + s + "@example.com", "U " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), org).getId();
        createdUsers.add(id);
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
