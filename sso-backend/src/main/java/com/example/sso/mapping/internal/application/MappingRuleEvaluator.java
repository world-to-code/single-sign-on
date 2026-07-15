package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.mapping.internal.domain.MappingRuleMembership;
import com.example.sso.mapping.internal.domain.MappingRuleMembershipRepository;
import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributePredicate;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.group.UserGroupService;
import java.util.HashSet;
import java.util.List;
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

    private static final String SYSTEM_PRINCIPAL = "system:mapping-rule";

    private final MappingRuleRepository rules;
    private final MappingRuleMembershipRepository memberships;
    private final AttributeService attributes;
    private final UserGroupService groups;
    private final AuditService audit;
    private final OrgTierGuard tierGuard;

    /** Reconcile ONE rule across the tier: add every matching user not yet claimed, retract every claim no longer matching. */
    @Transactional
    public void reevaluateRule(MappingRule rule) {
        Set<UUID> matching = matchingUsers(rule.getAttrKey(), rule.getAttrValue());
        Set<UUID> claimed = new HashSet<>();
        memberships.findByRuleId(rule.getId()).forEach(m -> claimed.add(m.getUserId()));

        Set<UUID> toAdd = matching.stream().filter(userId -> !claimed.contains(userId)).collect(Collectors.toSet());
        materializeAll(rule, toAdd); // one batched membership write + fan-out for the whole cohort
        claimed.stream().filter(userId -> !matching.contains(userId)).forEach(userId -> retract(rule, userId));
    }

    /** Reconcile every rule in the tier for ONE user whose attributes just changed. */
    @Transactional
    public void reevaluateUser(UUID userId) {
        UUID tier = tierGuard.currentTier();
        // Evaluate against the tier's OWN attributes only (not inherited globals), so this async path agrees with
        // the sync create/preview cohort (entityIdsWithInTier) — a platform-set global attribute never drives a
        // tenant rule.
        List<Attribute> userAttributes = attributes.attributesOfInTier(EntityKind.USER, userId.toString());
        Set<UUID> claimedRuleIds = memberships.findByUserId(userId).stream()
                .map(MappingRuleMembership::getRuleId).collect(Collectors.toSet()); // the user's claims in one query
        for (MappingRule rule : rules.findAll()) {
            if (!Objects.equals(rule.getOrgId(), tier)) {
                continue; // a user is governed only by rules in its OWN tier — a same-tier group is its only target
            }
            boolean matches = new AttributePredicate(rule.getAttrKey(), rule.getAttrValue()).matches(userAttributes);
            reconcile(rule, userId, matches, claimedRuleIds.contains(rule.getId()));
        }
    }

    /** The single add/retract decision, shared by both re-evaluation entry points so it can never drift. */
    private void reconcile(MappingRule rule, UUID userId, boolean matches, boolean claimed) {
        if (matches && !claimed) {
            materialize(rule, userId);
        } else if (!matches && claimed) {
            retract(rule, userId);
        }
    }

    /** Retract every membership a rule materialized (before the rule itself is deleted). */
    @Transactional
    public void retractAll(MappingRule rule) {
        memberships.findByRuleId(rule.getId()).forEach(m -> retract(rule, m.getUserId()));
    }

    /** The users the predicate matches in the acting tier ONLY (own users, never inherited global ones — a rule
     *  adds to a same-tier group, so a cross-tier user could never be a member). Dry run + reconcile source. */
    Set<UUID> matchingUsers(String attrKey, String attrValue) {
        return toUserIds(attributes.entityIdsWithInTier(EntityKind.USER, attrKey, attrValue));
    }

    private void materialize(MappingRule rule, UUID userId) {
        groups.addMember(rule.getGroupId(), userId);
        memberships.save(MappingRuleMembership.of(rule.getId(), userId, rule.getGroupId(), tierGuard.currentTier()));
        record(AuditType.MAPPING_RULE_APPLIED, rule, userId);
    }

    /** Materialize a whole cohort at once: one batched group-membership write, then provenance + audit per user. */
    private void materializeAll(MappingRule rule, Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        groups.addMembers(rule.getGroupId(), userIds);
        UUID tier = tierGuard.currentTier();
        memberships.saveAll(userIds.stream()
                .map(userId -> MappingRuleMembership.of(rule.getId(), userId, rule.getGroupId(), tier)).toList());
        userIds.forEach(userId -> record(AuditType.MAPPING_RULE_APPLIED, rule, userId));
    }

    private void retract(MappingRule rule, UUID userId) {
        List<MappingRuleMembership> claims = memberships.findByUserIdAndGroupId(userId, rule.getGroupId());
        memberships.findByRuleIdAndUserId(rule.getId(), userId).ifPresent(memberships::delete);
        boolean otherRuleClaims = claims.stream().anyMatch(claim -> !claim.getRuleId().equals(rule.getId()));
        if (!otherRuleClaims) {
            groups.removeMember(rule.getGroupId(), userId); // no rule still keeps them in the group
        }
        record(AuditType.MAPPING_RULE_RETRACTED, rule, userId);
    }

    private Set<UUID> toUserIds(Set<String> ids) {
        return ids.stream().map(UUID::fromString).collect(Collectors.toSet());
    }

    private void record(AuditType type, MappingRule rule, UUID userId) {
        String detail = "rule %s: user %s / group %s".formatted(rule.getId(), userId, rule.getGroupId());
        audit.record(new AuditRecord(type, SYSTEM_PRINCIPAL, true, detail, null,
                AuditSubjectType.USER, userId.toString(), rule.getOrgId()));
    }
}
