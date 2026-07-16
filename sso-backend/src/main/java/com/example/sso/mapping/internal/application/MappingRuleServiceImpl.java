package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.mapping.MappingCondition;
import com.example.sso.mapping.MappingRuleService;
import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingRuleView;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.mapping.internal.domain.MappingRuleCondition;
import com.example.sso.mapping.internal.domain.MappingRuleConditionRepository;
import com.example.sso.mapping.internal.domain.MappingRuleMembershipRepository;
import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.role.Roles;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link MappingRuleService}. CRUD over {@code mapping_rule} (+ its {@code mapping_rule_condition} rows)
 * in the acting tier, delegating the materialize/retract of assignments to {@link MappingRuleEvaluator} and the
 * per-kind target validation/labelling to a {@link MappingTargetApplier}. Validates the target exists in the
 * acting tier; the admin controller has already enforced that the actor may grant it.
 */
@Service
@RequiredArgsConstructor
class MappingRuleServiceImpl implements MappingRuleService {

    private final MappingRuleRepository rules;
    private final MappingRuleConditionRepository conditions;
    private final MappingRuleMembershipRepository memberships;
    private final MappingRuleEvaluator evaluator;
    private final List<MappingTargetApplier> appliers;
    private final OrgTierGuard tierGuard;
    private final AuditService audit;
    private final UserService users;

    @Override
    @Transactional
    public MappingRuleView create(MappingRuleSpec spec) {
        requireMappableConditions(spec.conditions());
        String targetName = applier(spec.thenKind()).validateInTier(spec.targetId());
        UUID tier = tierGuard.currentTier();
        MappingRule rule = MappingRule.of(spec.thenKind(), spec.targetId(), tier, resolveAuthor());
        rules.saveAndFlush(rule); // flush in-scope so RLS WITH CHECK stamps the acting tier
        writeConditions(rule.getId(), spec.conditions(), tier);
        evaluator.reevaluateRule(rule);
        audit(AuditType.MAPPING_RULE_CREATED, rule, describe(spec.conditions()));
        return view(rule, targetName);
    }

    @Override
    @Transactional
    public MappingRuleView update(UUID id, MappingRuleSpec spec) {
        requireMappableConditions(spec.conditions());
        MappingRule rule = requireInTierForUpdate(id); // lock before retract/repoint — serialize against async materialize
        String targetName = applier(spec.thenKind()).validateInTier(spec.targetId());
        if (!rule.getTargetId().equals(spec.targetId()) || rule.getThenKind() != spec.thenKind()) {
            evaluator.retractAll(rule); // clear the OLD target's assignments before repointing
        }
        rule.redefine(spec.thenKind(), spec.targetId());
        rule.restampAuthor(resolveAuthor()); // the updater re-authorizes the target, so they become the vouching author
        rules.saveAndFlush(rule);
        conditions.deleteByRuleId(rule.getId());     // replace the condition set wholesale
        conditions.flush();
        writeConditions(rule.getId(), spec.conditions(), tierGuard.currentTier());
        evaluator.reevaluateRule(rule);
        audit(AuditType.MAPPING_RULE_UPDATED, rule, describe(spec.conditions()));
        return view(rule, targetName);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        MappingRule rule = requireInTierForUpdate(id); // lock before retract — a racing materialize then skips (rule gone)
        String predicate = describe(conditionsOf(rule.getId())); // capture before the FK-cascade removes the conditions
        evaluator.retractAll(rule); // remove every assignment it materialized before dropping the rule
        rules.delete(rule);         // mapping_rule_condition rows cascade via the FK
        audit(AuditType.MAPPING_RULE_DELETED, rule, predicate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MappingRuleView> list() {
        return rules.findAll().stream().map(rule -> view(rule, targetName(rule))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MappingRuleView get(UUID id) {
        MappingRule rule = requireInTier(id);
        return view(rule, targetName(rule));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> preview(MappingRuleSpec spec) {
        requireMappableConditions(spec.conditions());
        return evaluator.matchingUsers(spec.conditions());
    }

    /** A rule needs at least one condition, and each targets a POSITIVE, index-able cohort only: EQUALS or EXISTS
     *  (a NOT_* cohort is "everyone without X" — unbounded and un-indexable). Enforced here too, not only in the
     *  request DTO. */
    private void requireMappableConditions(List<MappingCondition> ruleConditions) {
        if (ruleConditions.isEmpty()) {
            throw new BadRequestException("a mapping rule needs at least one condition");
        }
        if (!ruleConditions.stream().allMatch(c -> AttributeOperator.mappable(c.attrOp()))) {
            throw new BadRequestException("a mapping rule supports only EQUALS, EXISTS, IN or CONTAINS");
        }
    }

    private void writeConditions(UUID ruleId, List<MappingCondition> ruleConditions, UUID tier) {
        ruleConditions.forEach(c -> conditions.save(MappingRuleCondition.of(ruleId, c, tier)));
        conditions.flush(); // flush in-scope so RLS WITH CHECK stamps the acting tier on each condition row
    }

    private List<MappingCondition> conditionsOf(UUID ruleId) {
        return conditions.findByRuleId(ruleId).stream().map(MappingRuleCondition::toValue).toList();
    }

    private MappingRule requireInTier(UUID id) {
        return tierGuard.requireInTier(rules.findById(id), () -> new NotFoundException("mapping rule not found"));
    }

    /** As {@link #requireInTier} but under a row write-lock, so a mutation serializes against the async materialize. */
    private MappingRule requireInTierForUpdate(UUID id) {
        return tierGuard.requireInTier(rules.findByIdForUpdate(id), () -> new NotFoundException("mapping rule not found"));
    }

    private MappingTargetApplier applier(MappingTargetKind kind) {
        return appliers.stream().filter(a -> a.kind() == kind).findFirst()
                .orElseThrow(() -> new IllegalStateException("no applier for mapping kind " + kind));
    }

    private String targetName(MappingRule rule) {
        return applier(rule.getThenKind()).label(rule.getTargetId());
    }

    private MappingRuleView view(MappingRule rule, String targetName) {
        int assigned = (int) memberships.countByRuleId(rule.getId());
        return new MappingRuleView(rule.getId().toString(), conditionsOf(rule.getId()), rule.getThenKind(),
                rule.getTargetId().toString(), targetName, assigned);
    }

    /** A human-readable "k op v AND …" rendering of a rule's conditions for the audit trail. */
    private String describe(List<MappingCondition> ruleConditions) {
        return ruleConditions.stream().map(this::describeCondition).collect(Collectors.joining(" AND "));
    }

    private String describeCondition(MappingCondition c) {
        if (c.attrOp() == AttributeOperator.IN) {
            return "%s IN (%s)".formatted(c.attrKey(), String.join(", ", c.attrValues()));
        }
        return c.attrValue() == null
                ? "%s %s".formatted(c.attrKey(), c.attrOp())
                : "%s %s %s".formatted(c.attrKey(), c.attrOp(), c.attrValue());
    }

    /**
     * The acting admin's user id, to record as the rule's author for later re-validation. Mirrors
     * {@code AdminAccessPolicy.currentUserId} (which the create/update gate authorizes with): a platform
     * super-admin is a GLOBAL account, so resolve them globally rather than pick a same-named user in a
     * drilled-into org. Fail-open to null (a legacy/system author) never blocks the create — a mis-resolution
     * could only pick a lower-privileged same-named user, so re-validation stays fail-closed.
     */
    private UUID resolveAuthor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        boolean platformAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(Roles.ADMIN::equals);
        return (platformAdmin
                ? users.findByUsernameInOrg(authentication.getName(), null)
                : users.findByUsername(authentication.getName()))
                .map(UserAccount::getId).orElse(null);
    }

    private void audit(AuditType type, MappingRule rule, String predicate) {
        String principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName() : "system";
        String detail = "%s %s -> %s".formatted(rule.getThenKind(), predicate, rule.getTargetId());
        audit.record(new AuditRecord(type, principal, true, detail, null,
                AuditSubjectType.NONE, rule.getTargetId().toString(), rule.getOrgId()));
    }
}
