package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.mapping.MappingRuleService;
import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingRuleView;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.mapping.internal.domain.MappingRuleMembershipRepository;
import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link MappingRuleService}. CRUD over {@code mapping_rule} in the acting tier, delegating the
 * materialize/retract of assignments to {@link MappingRuleEvaluator} and the per-kind target validation/labelling
 * to a {@link MappingTargetApplier}. Validates the target exists in the acting tier; the admin controller has
 * already enforced that the actor may grant it.
 */
@Service
@RequiredArgsConstructor
class MappingRuleServiceImpl implements MappingRuleService {

    private final MappingRuleRepository rules;
    private final MappingRuleMembershipRepository memberships;
    private final MappingRuleEvaluator evaluator;
    private final List<MappingTargetApplier> appliers;
    private final OrgTierGuard tierGuard;
    private final AuditService audit;

    @Override
    @Transactional
    public MappingRuleView create(MappingRuleSpec spec) {
        String targetName = applier(spec.thenKind()).validateInTier(spec.targetId());
        MappingRule rule = MappingRule.of(spec.attrKey(), spec.attrValue(), spec.thenKind(), spec.targetId(),
                tierGuard.currentTier());
        rules.saveAndFlush(rule); // flush in-scope so RLS WITH CHECK stamps the acting tier
        evaluator.reevaluateRule(rule);
        audit(AuditType.MAPPING_RULE_CREATED, rule);
        return view(rule, targetName);
    }

    @Override
    @Transactional
    public MappingRuleView update(UUID id, MappingRuleSpec spec) {
        MappingRule rule = requireInTier(id);
        String targetName = applier(spec.thenKind()).validateInTier(spec.targetId());
        if (!rule.getTargetId().equals(spec.targetId()) || rule.getThenKind() != spec.thenKind()) {
            evaluator.retractAll(rule); // clear the OLD target's assignments before repointing
        }
        rule.redefine(spec.attrKey(), spec.attrValue(), spec.thenKind(), spec.targetId());
        rules.saveAndFlush(rule);
        evaluator.reevaluateRule(rule);
        audit(AuditType.MAPPING_RULE_UPDATED, rule);
        return view(rule, targetName);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        MappingRule rule = requireInTier(id);
        evaluator.retractAll(rule); // remove every assignment it materialized before dropping the rule
        rules.delete(rule);
        audit(AuditType.MAPPING_RULE_DELETED, rule);
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
        return evaluator.matchingUsers(spec.attrKey(), spec.attrValue());
    }

    private MappingRule requireInTier(UUID id) {
        return tierGuard.requireInTier(rules.findById(id), () -> new NotFoundException("mapping rule not found"));
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
        return new MappingRuleView(rule.getId().toString(), rule.getAttrKey(), rule.getAttrValue(),
                rule.getThenKind(), rule.getTargetId().toString(), targetName, assigned);
    }

    private void audit(AuditType type, MappingRule rule) {
        String principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName() : "system";
        String detail = "%s %s=%s -> %s".formatted(rule.getThenKind(), rule.getAttrKey(), rule.getAttrValue(),
                rule.getTargetId());
        audit.record(new AuditRecord(type, principal, true, detail, null,
                AuditSubjectType.NONE, rule.getTargetId().toString(), rule.getOrgId()));
    }
}
