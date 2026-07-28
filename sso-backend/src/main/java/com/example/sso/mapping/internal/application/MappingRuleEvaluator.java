package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditType;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.mapping.internal.domain.MappingRuleMembership;
import com.example.sso.mapping.internal.domain.MappingRuleMembershipRepository;
import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.metadata.Attribute;
import com.example.sso.mapping.MappingCondition;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.tenancy.OrgTierGuard;
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

    private final MappingRuleRepository rules;
    private final MappingCohortResolver cohorts;
    private final MappingGrantAdmission admission;
    private final MappingRuleMembershipRepository memberships;
    private final List<MappingTargetApplier> appliers;
    private final OrgTierGuard tierGuard;
    private final RetractionAdminGuard retractionGuard;
    private final MappingAuditTrail trail;

    /** Reconcile ONE rule across the tier: add every matching user not yet claimed, retract every claim no longer matching. */
    @Transactional
    public void reevaluateRule(MappingRule rule) {
        RetractionScope scope = retractionGuard.before(List.of(rule)); // BEFORE any mutation
        Set<UUID> matching = cohorts.matchingUsers(cohorts.conditionsOf(rule.getId()));
        Set<UUID> claimed = new HashSet<>();
        memberships.findByRuleId(rule.getId()).forEach(m -> claimed.add(m.getUserId()));

        Set<UUID> toAdd = matching.stream().filter(userId -> !claimed.contains(userId)).collect(Collectors.toSet());
        materializeAll(rule, toAdd); // one batched membership write + fan-out for the whole cohort
        Set<UUID> retracted = claimed.stream().filter(userId -> !matching.contains(userId))
                .map(userId -> retract(rule, userId))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        scope.assertTierKeepsAnAdmin(retracted);
    }

    /** Reconcile every rule in the tier for ONE user whose attributes just changed. */
    @Transactional
    public void reevaluateUser(UUID userId) {
        UUID tier = tierGuard.currentTier();
        // Evaluate against the user's OWN attributes UNIONED with those inherited from their groups, all own-tier
        // only (never inherited globals) — so this async path agrees with the sync cohort below, and a
        // platform-set global attribute never drives a tenant rule.
        List<Attribute> userAttributes = cohorts.effectiveAttributes(userId);
        Set<UUID> claimedRuleIds = memberships.findByUserId(userId).stream()
                .map(MappingRuleMembership::getRuleId).collect(Collectors.toSet()); // the user's claims in one query
        // Every tier rule's conditions in ONE query, grouped by rule — avoids a per-rule fetch in the loop below.
        Map<UUID, List<MappingCondition>> conditionsByRule = cohorts.allConditionsGroupedByRule();
        // A user is governed only by rules in its OWN tier — a same-tier group is its only target. Stable id
        // order so concurrent re-evaluations acquire the per-rule locks (in materialize) in the same sequence —
        // a lock-order cycle can't form, only a clean wait the loser's retry/sweep re-drives.
        List<MappingRule> tierRules = rules.findAll().stream()
                .filter(rule -> Objects.equals(rule.getOrgId(), tier))
                .sorted(Comparator.comparing(MappingRule::getId)).toList();
        // Only what this user is already CLAIMED by can be retracted, and the read must precede the loop.
        RetractionScope scope = retractionGuard.before(
                tierRules.stream().filter(rule -> claimedRuleIds.contains(rule.getId())).toList());

        Set<UUID> retracted = new HashSet<>();
        for (MappingRule rule : tierRules) {
            boolean matches = cohorts.matchesAll(conditionsByRule.getOrDefault(rule.getId(), List.of()), userAttributes);
            UUID went = reconcile(rule, userId, matches, claimedRuleIds.contains(rule.getId()));
            if (went != null) {
                retracted.add(went);
            }
        }
        scope.assertTierKeepsAnAdmin(retracted);
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
        RetractionScope scope = retractionGuard.before(claimed); // BEFORE any mutation
        Set<UUID> retracted = new HashSet<>();
        boolean inheritedAttributesShrank = true;
        while (inheritedAttributesShrank && !claimed.isEmpty()) {
            inheritedAttributesShrank = false;
            List<Attribute> effective = cohorts.effectiveAttributes(userId); // re-read: a lost group lends less
            Map<UUID, List<MappingCondition>> byRule = cohorts.conditionsGroupedByRule(claimed);
            List<MappingRule> stillMatching = new ArrayList<>();
            for (MappingRule rule : claimed) {
                if (cohorts.matchesAll(byRule.getOrDefault(rule.getId(), List.of()), effective)) {
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
        scope.assertTierKeepsAnAdmin(retracted);
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
        RetractionScope scope = retractionGuard.before(List.of(rule)); // BEFORE any mutation
        Set<UUID> retracted = memberships.findByRuleId(rule.getId()).stream()
                .map(m -> retract(rule, m.getUserId()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        scope.assertTierKeepsAnAdmin(retracted);
    }

    private void materialize(MappingRule rule, UUID userId) {
        if (rules.findByIdForUpdate(rule.getId()).isEmpty()) {
            return; // deleted concurrently — hold the row lock to serialize against a racing delete/retract
        }
        if (!admission.admits(rule)) {
            return; // the author lost the authority, or nobody vouched for the directory deciding the match
        }
        // Claim FIRST (ON CONFLICT DO NOTHING): only the tx that actually inserts the provenance row grants and
        // audits, so a concurrent twin neither re-grants, double-audits, nor aborts on the unique constraint.
        if (memberships.insertClaimIfAbsent(rule.getId(), userId, rule.getTargetId(), tierGuard.currentTier()) == 0) {
            return;
        }
        applierFor(rule).assign(rule.getTargetId(), userId);
        trail.changedMembership(AuditType.MAPPING_RULE_APPLIED, rule, userId);
        admission.noteLegacyAuthor(rule);
    }

    /** Materialize a whole cohort at once: claim each provenance row, then one batched grant for the newly-claimed. */
    private void materializeAll(MappingRule rule, Set<UUID> userIds) {
        if (userIds.isEmpty() || rules.findByIdForUpdate(rule.getId()).isEmpty()) {
            return;
        }
        if (!admission.admits(rule)) {
            return; // asked ONCE for the whole cohort — same question, same answer, audited once
        }
        UUID tier = tierGuard.currentTier();
        Set<UUID> newlyClaimed = userIds.stream()
                .filter(userId -> memberships.insertClaimIfAbsent(rule.getId(), userId, rule.getTargetId(), tier) == 1)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (newlyClaimed.isEmpty()) {
            return;
        }
        applierFor(rule).assignAll(rule.getTargetId(), newlyClaimed);
        newlyClaimed.forEach(userId -> trail.changedMembership(AuditType.MAPPING_RULE_APPLIED, rule, userId));
        admission.noteLegacyAuthor(rule);
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
        trail.changedMembership(AuditType.MAPPING_RULE_RETRACTED, rule, userId);
        return targetThatWent;
    }

    private MappingTargetApplier applierFor(MappingRule rule) {
        return appliers.stream().filter(a -> a.kind() == rule.getThenKind()).findFirst()
                .orElseThrow(() -> new IllegalStateException("no applier for mapping kind " + rule.getThenKind()));
    }

}
