package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.MappingCondition;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.mapping.internal.domain.MappingRuleCondition;
import com.example.sso.mapping.internal.domain.MappingRuleConditionRepository;
import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.user.group.UserGroupService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who a rule's conditions match — the read-only half of the mapping engine, answered two ways.
 *
 * <p>{@link #matchingUsers} works forwards from the conditions to a cohort, which is what a rule edit and the
 * dry-run preview need. {@link #matchesAll} works backwards from one user's attributes, which is what a
 * per-user re-evaluation needs. Both had to agree, and keeping them in the same class as the WRITES made that
 * agreement something a reader had to reconstruct from a 490-line file.
 *
 * <p>Everything here is tier-scoped by RLS through the acting context: own users and own tags only, never
 * inherited globals — a rule adds to a same-tier group, so a cross-tier user could never be a member anyway.
 * Nothing in this class mutates.
 */
@Component
@RequiredArgsConstructor
class MappingCohortResolver {

    private final AttributeService attributes;
    private final UserGroupService userGroups;
    private final MappingRuleConditionRepository conditions;

    /** The users satisfying ALL conditions: the INTERSECTION of each condition's cohort. Empty list = nobody. */
    Set<UUID> matchingUsers(List<MappingCondition> ruleConditions) {
        Set<UUID> cohort = null;
        for (MappingCondition condition : ruleConditions) {
            Set<UUID> conditionCohort = cohortOf(condition);
            cohort = cohort == null ? conditionCohort : intersect(cohort, conditionCohort);
            if (cohort.isEmpty()) {
                break; // AND: once a condition contributes nobody, the whole rule matches nobody
            }
        }
        return cohort == null ? Set.of() : cohort;
    }

    /** AND semantics from the other direction: this user satisfies EVERY condition. An empty list never
     *  matches (a rule always has ≥1; this guards the vacuous all-match). */
    boolean matchesAll(List<MappingCondition> ruleConditions, List<Attribute> userAttributes) {
        return !ruleConditions.isEmpty()
                && ruleConditions.stream().allMatch(condition -> condition.toPredicate().matches(userAttributes));
    }

    /** A user's OWN own-tier attributes unioned with those inherited from the groups they belong to. */
    List<Attribute> effectiveAttributes(UUID userId) {
        List<Attribute> effective = new ArrayList<>(attributes.attributesOfInTier(EntityKind.USER, userId.toString()));
        Set<UUID> groupIds = userGroups.groupIdsOf(userId);
        if (!groupIds.isEmpty()) {
            effective.addAll(attributes.unionAttributesOfInTier(EntityKind.GROUP,
                    groupIds.stream().map(UUID::toString).toList()));
        }
        return effective;
    }

    List<MappingCondition> conditionsOf(UUID ruleId) {
        return conditions.findByRuleId(ruleId).stream().map(MappingRuleCondition::toValue).toList();
    }

    /** EVERY tier rule's conditions in one query — the async reconcile reads the whole tier, unlike the
     *  claim-scoped pass below. */
    Map<UUID, List<MappingCondition>> allConditionsGroupedByRule() {
        return grouped(conditions.findAll());
    }

    /** A KNOWN set of rules' conditions in ONE query, grouped by rule — no per-rule fetch inside a loop. */
    Map<UUID, List<MappingCondition>> conditionsGroupedByRule(Collection<MappingRule> ruleSet) {
        return grouped(conditions.findByRuleIdIn(ruleSet.stream().map(MappingRule::getId).toList()));
    }

    private Map<UUID, List<MappingCondition>> grouped(List<MappingRuleCondition> rows) {
        return rows.stream().collect(Collectors.groupingBy(MappingRuleCondition::getRuleId,
                Collectors.mapping(MappingRuleCondition::toValue, Collectors.toList())));
    }

    /** The users one condition matches: those carrying the attribute DIRECTLY (USER entities) UNIONED with the
     *  members of any GROUP carrying it (inheritance). Both sides are the same tier-scoped entity query on a
     *  different kind, so global tags never leak in and members stay same-org. */
    private Set<UUID> cohortOf(MappingCondition condition) {
        Set<UUID> cohort = new HashSet<>(toUserIds(entityIdsFor(condition, EntityKind.USER)));
        Set<String> matchingGroupIds = entityIdsFor(condition, EntityKind.GROUP);
        if (!matchingGroupIds.isEmpty()) {
            cohort.addAll(userGroups.memberIdsOf(matchingGroupIds.stream().map(UUID::fromString).toList()));
        }
        return cohort;
    }

    /** The ids of the entities of {@code kind} the condition matches. Exhaustive over the operator — a new one
     *  is a compile error, and the un-mappable NOT_* operators are a can't-happen invariant. */
    private Set<String> entityIdsFor(MappingCondition condition, EntityKind kind) {
        String key = condition.attrKey();
        return switch (condition.attrOp()) {
            case EQUALS -> attributes.entityIdsWithInTier(kind, key, condition.attrValue());
            case EXISTS -> attributes.entityIdsWithKeyInTier(kind, key);
            case IN -> attributes.entityIdsWithAnyValueInTier(kind, key, condition.attrValues());
            case CONTAINS -> attributes.entityIdsWithValueContainingInTier(kind, key, condition.attrValue());
            case NOT_EQUALS, NOT_EXISTS ->
                    throw new IllegalStateException("un-mappable operator reached a cohort: " + condition.attrOp());
        };
    }

    private Set<UUID> intersect(Set<UUID> a, Set<UUID> b) {
        return a.stream().filter(b::contains).collect(Collectors.toSet());
    }

    private Set<UUID> toUserIds(Set<String> ids) {
        return ids.stream().map(UUID::fromString).collect(Collectors.toSet());
    }
}
