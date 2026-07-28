package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.mapping.MappingCondition;
import com.example.sso.mapping.MappingTargetAuthority;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.mapping.internal.domain.MappingRuleCondition;
import com.example.sso.mapping.internal.domain.MappingRuleConditionRepository;
import com.example.sso.mapping.internal.domain.MappingRuleMembership;
import com.example.sso.mapping.internal.domain.MappingRuleMembershipRepository;
import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeService;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeSourceAuthority;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.EntityKind;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.deny.LastAdminInvariant;
import com.example.sso.user.group.UserGroupService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes and retracts the group memberships a {@link MappingRule} implies, keeping the target group's
 * rule-managed members in sync with who currently matches the predicate. Every add/remove is recorded in the
 * provenance table ({@link MappingRuleMembership}) so a retract removes ONLY rule-managed rows: a member is
 * dropped from the group when no rule still claims them, leaving manually-added members untouched (the one
 * exception — a user both manually added AND rule-matched — is removed when the last matching rule stops
 * matching; documented trade-off, continuous manual/rule co-ownership is a follow-up).
 *
 * <p>All methods run in the ACTING TIER already established by the caller (a request scope for rule edits, or
 * {@code callInOrg} for the async attribute-change listener); RLS confines every read/write to that tenant.
 */
@Service
@RequiredArgsConstructor
class MappingRuleEvaluator {

    private static final String SYSTEM_PRINCIPAL = "system:mapping-rule";

    private final MappingRuleRepository rules;
    private final MappingRuleConditionRepository conditions;
    private final AttributeDefinitionService definitions;
    private final AttributeSourceAuthority sources;
    private final MappingRuleMembershipRepository memberships;
    private final AttributeService attributes;
    private final List<MappingTargetApplier> appliers;
    private final AuditService audit;
    private final OrgTierGuard tierGuard;
    private final MappingTargetAuthority targetAuthority;
    private final UserGroupService userGroups;
    private final LastAdminInvariant lastAdminInvariant;
    private final ApplicationEventPublisher events;

    /** Reconcile ONE rule across the tier: add every matching user not yet claimed, retract every claim no longer matching. */
    @Transactional
    public void reevaluateRule(MappingRule rule) {
        boolean guarding = retractionNeedsGuarding(privilegeTargetsOf(List.of(rule))); // BEFORE any mutation
        Set<UUID> matching = matchingUsers(conditionsOf(rule.getId()));
        Set<UUID> claimed = new HashSet<>();
        memberships.findByRuleId(rule.getId()).forEach(m -> claimed.add(m.getUserId()));

        Set<UUID> toAdd = matching.stream().filter(userId -> !claimed.contains(userId)).collect(Collectors.toSet());
        materializeAll(rule, toAdd); // one batched membership write + fan-out for the whole cohort
        Set<UUID> retracted = claimed.stream().filter(userId -> !matching.contains(userId))
                .map(userId -> retract(rule, userId))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        assertTierKeepsAnAdmin(guarding, retracted);
    }

    /** Reconcile every rule in the tier for ONE user whose attributes just changed. */
    @Transactional
    public void reevaluateUser(UUID userId) {
        UUID tier = tierGuard.currentTier();
        // Evaluate against the user's OWN attributes UNIONED with those inherited from their groups, all own-tier
        // only (never inherited globals) — so this async path agrees with the sync cohort below, and a
        // platform-set global attribute never drives a tenant rule.
        List<Attribute> userAttributes = effectiveAttributes(userId);
        Set<UUID> claimedRuleIds = memberships.findByUserId(userId).stream()
                .map(MappingRuleMembership::getRuleId).collect(Collectors.toSet()); // the user's claims in one query
        // Every tier rule's conditions in ONE query, grouped by rule — avoids a per-rule fetch in the loop below.
        Map<UUID, List<MappingCondition>> conditionsByRule = conditions.findAll().stream().collect(
                Collectors.groupingBy(MappingRuleCondition::getRuleId,
                        Collectors.mapping(MappingRuleCondition::toValue, Collectors.toList())));
        // A user is governed only by rules in its OWN tier — a same-tier group is its only target. Stable id
        // order so concurrent re-evaluations acquire the per-rule locks (in materialize) in the same sequence —
        // a lock-order cycle can't form, only a clean wait the loser's retry/sweep re-drives.
        List<MappingRule> tierRules = rules.findAll().stream()
                .filter(rule -> Objects.equals(rule.getOrgId(), tier))
                .sorted(Comparator.comparing(MappingRule::getId)).toList();
        // Only what this user is already CLAIMED by can be retracted, and the read must precede the loop.
        boolean guarding = retractionNeedsGuarding(privilegeTargetsOf(
                tierRules.stream().filter(rule -> claimedRuleIds.contains(rule.getId())).toList()));

        Set<UUID> retracted = new HashSet<>();
        for (MappingRule rule : tierRules) {
            boolean matches = matchesAll(conditionsByRule.getOrDefault(rule.getId(), List.of()), userAttributes);
            UUID went = reconcile(rule, userId, matches, claimedRuleIds.contains(rule.getId()));
            if (went != null) {
                retracted.add(went);
            }
        }
        assertTierKeepsAnAdmin(guarding, retracted);
    }

    /**
     * Retract, to a fixed point, every claim this user no longer qualifies for. The SYNCHRONOUS pass, run
     * inside a write that has just deleted the attributes those rules read.
     *
     * <p>Deliberately not {@link #reevaluateUser}. That one is the general reconcile and is the wrong shape to
     * put inside an interactive write: it reads every rule and every condition in the tier, and its
     * materialize branch is reachable, which takes a {@code SELECT FOR UPDATE} on each rule row. This reads
     * only the rules that already CLAIM this user and never materializes — a deletion can only ever un-match,
     * since mapping operators are positive-only — so it takes no row locks at all, and the tier lock behind
     * the last-administrator guard is only reached when an admin-bearing role actually goes.
     *
     * <p>Iterated rather than single-pass because retracting a GROUP takes back the attributes that group lent
     * the user, and another claimed rule may have been matching on one of those. A single pass reads the
     * attribute set once and so retracts the group while leaving the role behind it in place — the IdP would
     * then declare the person logged out while {@code app_user_role} still carried the role. Only a GROUP can
     * shrink the attribute set, so only a GROUP retraction buys another round, and each round retracts at
     * least one claim, so the loop is bounded by the claims the user started with.
     */
    @Transactional
    public void retractStaleClaims(UUID userId) {
        List<MappingRule> claimed = claimedRulesOf(userId);
        if (claimed.isEmpty()) {
            return;
        }
        boolean guarding = retractionNeedsGuarding(privilegeTargetsOf(claimed)); // BEFORE any mutation
        Set<UUID> retracted = new HashSet<>();
        boolean inheritedAttributesShrank = true;
        while (inheritedAttributesShrank && !claimed.isEmpty()) {
            inheritedAttributesShrank = false;
            List<Attribute> effective = effectiveAttributes(userId); // re-read: a lost group lends less
            Map<UUID, List<MappingCondition>> byRule = conditionsGroupedByRule(claimed);
            List<MappingRule> stillMatching = new ArrayList<>();
            for (MappingRule rule : claimed) {
                if (matchesAll(byRule.getOrDefault(rule.getId(), List.of()), effective)) {
                    stillMatching.add(rule);
                    continue;
                }
                UUID went = retract(rule, userId);
                if (went != null) {
                    retracted.add(went);
                }
                // Another round only if the group actually WENT: a retract whose target another rule still
                // claims leaves the membership in place, so the inherited attributes are unchanged and the
                // extra round is four queries for a guaranteed no-op.
                inheritedAttributesShrank |= went != null && rule.getThenKind() == MappingTargetKind.GROUP;
            }
            claimed = stillMatching;
        }
        assertTierKeepsAnAdmin(guarding, retracted);
    }

    /** The tier's rules that already claim this user — the only ones a retraction pass can act on. */
    private List<MappingRule> claimedRulesOf(UUID userId) {
        Set<UUID> claimedRuleIds = memberships.findByUserId(userId).stream()
                .map(MappingRuleMembership::getRuleId).collect(Collectors.toSet());
        if (claimedRuleIds.isEmpty()) {
            return List.of();
        }
        UUID tier = tierGuard.currentTier();
        return rules.findAllById(claimedRuleIds).stream()
                .filter(rule -> Objects.equals(rule.getOrgId(), tier)) // own-tier rules govern a user, only
                .toList();
    }

    /** Those rules' conditions in ONE query, grouped by rule. */
    private Map<UUID, List<MappingCondition>> conditionsGroupedByRule(Collection<MappingRule> ruleSet) {
        return conditions.findByRuleIdIn(ruleSet.stream().map(MappingRule::getId).toList()).stream()
                .collect(Collectors.groupingBy(MappingRuleCondition::getRuleId,
                        Collectors.mapping(MappingRuleCondition::toValue, Collectors.toList())));
    }

    /** AND semantics: the user satisfies EVERY condition. An empty condition list never matches (a rule always
     *  has ≥1; this guards the vacuous all-match). */
    private boolean matchesAll(List<MappingCondition> ruleConditions, List<Attribute> userAttributes) {
        return !ruleConditions.isEmpty()
                && ruleConditions.stream().allMatch(condition -> condition.toPredicate().matches(userAttributes));
    }

    /** The single add/retract decision, shared by both re-evaluation entry points so it can never drift.
     *  Answers whether a membership actually went, so the caller can recount the tier's admins once. */
    private UUID reconcile(MappingRule rule, UUID userId, boolean matches, boolean claimed) {
        if (matches && !claimed) {
            materialize(rule, userId);
        } else if (!matches && claimed) {
            return retract(rule, userId);
        }
        return null;
    }

    /** Retract every membership a rule materialized (before the rule itself is deleted). */
    @Transactional
    public void retractAll(MappingRule rule) {
        boolean guarding = retractionNeedsGuarding(privilegeTargetsOf(List.of(rule))); // BEFORE any mutation
        Set<UUID> retracted = memberships.findByRuleId(rule.getId()).stream()
                .map(m -> retract(rule, m.getUserId()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        assertTierKeepsAnAdmin(guarding, retracted);
    }

    /** The users satisfying ALL conditions in the acting tier ONLY (own users, never inherited global ones — a
     *  rule adds to a same-tier group, so a cross-tier user could never be a member): the INTERSECTION of each
     *  condition's cohort. Dry run (preview) + reconcile source. An empty condition list matches nobody. */
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

    /** The users one condition matches in the acting tier: those carrying the attribute DIRECTLY (USER entities)
     *  UNIONED with the members of any GROUP carrying it (inheritance). Both sides are the same tier-scoped
     *  entity query, just on a different kind, so global tags never leak in and members stay same-org. */
    private Set<UUID> cohortOf(MappingCondition condition) {
        Set<UUID> cohort = new HashSet<>(toUserIds(entityIdsFor(condition, EntityKind.USER)));
        Set<String> matchingGroupIds = entityIdsFor(condition, EntityKind.GROUP);
        if (!matchingGroupIds.isEmpty()) {
            cohort.addAll(userGroups.memberIdsOf(matchingGroupIds.stream().map(UUID::fromString).toList()));
        }
        return cohort;
    }

    /** The ids of the entities of {@code kind} the condition matches in the acting tier. Exhaustive over the
     *  operator — a new one is a compile error, and the un-mappable NOT_* operators are a can't-happen invariant. */
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

    /** A user's OWN own-tier attributes unioned with those inherited from the groups they belong to. */
    private List<Attribute> effectiveAttributes(UUID userId) {
        List<Attribute> effective = new ArrayList<>(attributes.attributesOfInTier(EntityKind.USER, userId.toString()));
        Set<UUID> groupIds = userGroups.groupIdsOf(userId);
        if (!groupIds.isEmpty()) {
            effective.addAll(attributes.unionAttributesOfInTier(EntityKind.GROUP,
                    groupIds.stream().map(UUID::toString).toList()));
        }
        return effective;
    }

    private Set<UUID> intersect(Set<UUID> a, Set<UUID> b) {
        return a.stream().filter(b::contains).collect(Collectors.toSet());
    }

    private List<MappingCondition> conditionsOf(UUID ruleId) {
        return conditions.findByRuleId(ruleId).stream().map(MappingRuleCondition::toValue).toList();
    }

    private void materialize(MappingRule rule, UUID userId) {
        if (rules.findByIdForUpdate(rule.getId()).isEmpty()) {
            return; // deleted concurrently — hold the row lock to serialize against a racing delete/retract
        }
        if (!authorStillAuthorized(rule)) {
            return; // the author lost the authority this grant would need — skip (audited)
        }
        if (!directorySourcesAuthorized(rule)) {
            return; // a directory decides who matches this rule, and nobody vouched for it (audited once)
        }
        // Claim FIRST (ON CONFLICT DO NOTHING): only the tx that actually inserts the provenance row grants and
        // audits, so a concurrent twin neither re-grants, double-audits, nor aborts on the unique constraint.
        if (memberships.insertClaimIfAbsent(rule.getId(), userId, rule.getTargetId(), tierGuard.currentTier()) == 0) {
            return;
        }
        applierFor(rule).assign(rule.getTargetId(), userId);
        record(AuditType.MAPPING_RULE_APPLIED, rule, userId);
        noteLegacyAuthor(rule);
    }

    /** Materialize a whole cohort at once: claim each provenance row, then one batched grant for the newly-claimed. */
    private void materializeAll(MappingRule rule, Set<UUID> userIds) {
        if (userIds.isEmpty() || rules.findByIdForUpdate(rule.getId()).isEmpty()) {
            return;
        }
        if (!authorStillAuthorized(rule)) {
            return; // author lost authority for the whole cohort — skip (audited once)
        }
        if (!directorySourcesAuthorized(rule)) {
            return; // a directory decides who matches this rule, and nobody vouched for it (audited once)
        }
        UUID tier = tierGuard.currentTier();
        Set<UUID> newlyClaimed = userIds.stream()
                .filter(userId -> memberships.insertClaimIfAbsent(rule.getId(), userId, rule.getTargetId(), tier) == 1)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (newlyClaimed.isEmpty()) {
            return;
        }
        applierFor(rule).assignAll(rule.getTargetId(), newlyClaimed);
        newlyClaimed.forEach(userId -> record(AuditType.MAPPING_RULE_APPLIED, rule, userId));
        noteLegacyAuthor(rule);
    }

    /**
     * Zero-trust re-check at grant time: the author must STILL hold the authority the create/update gate demanded
     * — a since-demoted or deleted author's rule must stop handing out grants they could no longer make by hand.
     * A rule with no recorded author (legacy/system) is allowed (audited on the grant). A lost-authority author
     * is denied and audited. Fails CLOSED (an unresolvable author yields no authority → false in the port).
     */
    private boolean authorStillAuthorized(MappingRule rule) {
        if (rule.getCreatedBy() == null) {
            return true; // legacy/system rule — allowed; noteLegacyAuthor marks the grant for backfill visibility
        }
        if (targetAuthority.authorMayAssign(rule.getCreatedBy(), rule.getThenKind(), rule.getTargetId())) {
            return true;
        }
        recordAuthor(AuditType.MAPPING_RULE_AUTHOR_UNAUTHORIZED, rule);
        return false;
    }

    /**
     * The second question the author check cannot ask: WHO decided that this user matches?
     *
     * <p>When a rule's conditions read an attribute a DIRECTORY owns, the person who aimed that directory
     * chooses which users satisfy the rule — without ever needing authority over its target. Holding only
     * {@code directory-connector:write} would otherwise be enough to point a connector at a directory you
     * control, assert the matching value for yourself, and collect whatever an existing, entirely legitimate
     * rule grants. So every connector that can fill those attributes must have been configured by someone who
     * could have made this grant by hand.
     *
     * <p>Only privilege-granting targets are gated: a RESOURCE_MEMBER rule confers no authority. Fails CLOSED —
     * a connector with no recorded configurator vouches for nothing.
     */
    private boolean directorySourcesAuthorized(MappingRule rule) {
        if (rule.getThenKind() == MappingTargetKind.RESOURCE_MEMBER) {
            return true;
        }
        Set<String> directoryKeys = conditionsOf(rule.getId()).stream()
                .map(MappingCondition::attrKey)
                .filter(key -> definitions.definitionOf(EntityKind.USER, key)
                        .filter(definition -> !definition.locallyEditable())
                        .isPresent())
                .collect(Collectors.toSet());
        if (directoryKeys.isEmpty()) {
            return true; // no directory decides who matches this rule
        }
        AttributeSourceAuthors authors = sources.authorsFilling(directoryKeys);
        boolean authorized = authors.fullyAttributed()
                && authors.configurators().stream().allMatch(configurator ->
                        targetAuthority.authorMayAssign(configurator, rule.getThenKind(), rule.getTargetId()));
        if (!authorized) {
            recordAuthor(AuditType.MAPPING_RULE_DIRECTORY_SOURCE_UNAUTHORIZED, rule);
        }
        return authorized;
    }

    private void noteLegacyAuthor(MappingRule rule) {
        if (rule.getCreatedBy() == null) {
            recordAuthor(AuditType.MAPPING_RULE_LEGACY_AUTHOR, rule);
        }
    }

    private UUID retract(MappingRule rule, UUID userId) {
        List<MappingRuleMembership> claims = memberships.findByUserIdAndTargetId(userId, rule.getTargetId());
        memberships.findByRuleIdAndUserId(rule.getId(), userId).ifPresent(memberships::delete);
        boolean otherRuleClaims = claims.stream().anyMatch(claim -> !claim.getRuleId().equals(rule.getId()));
        UUID targetThatWent = null;
        if (!otherRuleClaims) {
            applierFor(rule).unassign(rule.getTargetId(), userId); // no rule still keeps them on the target
            targetThatWent = rule.getThenKind() == MappingTargetKind.RESOURCE_MEMBER ? null : rule.getTargetId();
        }
        record(AuditType.MAPPING_RULE_RETRACTED, rule, userId);
        return targetThatWent;
    }

    /**
     * Whether a retraction in this transaction has to answer to the last-admin invariant — asked BEFORE
     * anything is retracted, because once the memberships are gone the recount can no longer tell "this write
     * took the tier's last administrator" from "the tier never had one". See
     * {@link LastAdminInvariant#retractionWouldNeedGuarding}.
     */
    private boolean retractionNeedsGuarding(Collection<UUID> candidateTargets) {
        return lastAdminInvariant.retractionWouldNeedGuarding(rolesBehind(candidateTargets),
                tierGuard.currentTier());
    }

    /**
     * A retraction reaches the domain service directly, below every guard the console path goes through — so
     * the tier could lose its last administrator here and nowhere else would notice. Asked ONCE per
     * transaction rather than per user: the recount is a query, and a cohort retraction would otherwise pay it
     * per member for one logical change.
     *
     * <p>Rejecting rolls the whole re-evaluation back, which is the deliberate trade the deny path already
     * makes: somebody keeps a role they no longer qualify for — visible, and fixable by an administrator —
     * instead of the tier having none, which is recoverable only by a platform super.
     */
    private void assertTierKeepsAnAdmin(boolean guarding, Collection<UUID> retractedTargets) {
        if (!guarding || retractedTargets.isEmpty()) {
            return;
        }
        try {
            lastAdminInvariant.ensureRetractionRetainsAdmin(tierGuard.currentTier());
        } catch (RuntimeException refused) {
            recordRefusal(retractedTargets, refused);
            throw refused;
        }
    }

    /**
     * Written INLINE, unlike every other row here: this one has to outlive the rollback it is about.
     *
     * <p>The changes are dropped with the transaction, correctly — they did not happen. But without this the
     * refusal leaves no trace at all, and on the asynchronous path the exception is swallowed by the executor
     * and the sweep re-drives the same rejected transaction on its next pass. What an operator sees is a rule
     * that has silently stopped converging, with nothing anywhere to say why.
     */
    private void recordRefusal(Collection<UUID> retractedTargets, RuntimeException refused) {
        UUID tier = tierGuard.currentTier();
        String detail = ("re-evaluation rolled back in tier %s: retracting %d target(s) "
                + "would have left it with no administrator").formatted(tier, retractedTargets.size());
        // getMessage() is the exception's message KEY, not anything a caller supplied.
        audit.record(new AuditRecord(AuditType.MAPPING_RULE_RETRACTION_REFUSED, SYSTEM_PRINCIPAL, false, detail,
                null, AuditSubjectType.NONE, null, tier).withReason(refused.getMessage()));
    }

    /** The targets whose loss could take authority away — a RESOURCE_MEMBER rule confers none. */
    private Set<UUID> privilegeTargetsOf(Collection<MappingRule> candidates) {
        return candidates.stream()
                .filter(rule -> rule.getThenKind() != MappingTargetKind.RESOURCE_MEMBER)
                .map(MappingRule::getTargetId)
                .collect(Collectors.toSet());
    }

    /** What a retracted target actually takes away: a ROLE is itself, a GROUP is the roles it delegates. */
    private Set<UUID> rolesBehind(Collection<UUID> targetIds) {
        if (targetIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> roleIds = new HashSet<>(targetIds);
        userGroups.delegatedRoleIds(targetIds).values().forEach(roleIds::addAll);
        return roleIds;
    }

    private MappingTargetApplier applierFor(MappingRule rule) {
        return appliers.stream().filter(a -> a.kind() == rule.getThenKind()).findFirst()
                .orElseThrow(() -> new IllegalStateException("no applier for mapping kind " + rule.getThenKind()));
    }

    private Set<UUID> toUserIds(Set<String> ids) {
        return ids.stream().map(UUID::fromString).collect(Collectors.toSet());
    }

    private void record(AuditType type, MappingRule rule, UUID userId) {
        String detail = "rule %s (%s): user %s / target %s"
                .formatted(rule.getId(), rule.getThenKind(), userId, rule.getTargetId());
        pending(new AuditRecord(type, SYSTEM_PRINCIPAL, true, detail, null,
                AuditSubjectType.USER, userId.toString(), rule.getOrgId()));
    }

    /** A rule-level audit (no per-user subject): the author re-validation outcome for the whole grant. */
    private void recordAuthor(AuditType type, MappingRule rule) {
        String detail = "rule %s (%s): target %s / author %s"
                .formatted(rule.getId(), rule.getThenKind(), rule.getTargetId(), rule.getCreatedBy());
        pending(new AuditRecord(type, SYSTEM_PRINCIPAL, true, detail, null,
                AuditSubjectType.NONE, rule.getTargetId().toString(), rule.getOrgId()));
    }

    /** Every row about a CHANGE goes through here, so none of them can outlive a rollback — see
     *  {@link MappingAuditPending}. */
    private void pending(AuditRecord record) {
        events.publishEvent(new MappingAuditPending(record));
    }
}
