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
import com.example.sso.shared.IdName;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.group.GroupView;
import com.example.sso.user.group.UserGroupService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link MappingRuleService}. CRUD over {@code mapping_rule} in the acting tier, delegating the
 * materialize/retract of group memberships to {@link MappingRuleEvaluator}. Validates that the target group
 * exists in the acting tier and is not a system group; the caller (admin controller) has already enforced that
 * the actor may grant the group's membership.
 */
@Service
@RequiredArgsConstructor
class MappingRuleServiceImpl implements MappingRuleService {

    private final MappingRuleRepository rules;
    private final MappingRuleMembershipRepository memberships;
    private final MappingRuleEvaluator evaluator;
    private final UserGroupService groups;
    private final OrgTierGuard tierGuard;
    private final AuditService audit;

    @Override
    @Transactional
    public MappingRuleView create(MappingRuleSpec spec) {
        String groupName = requireGroupInTier(spec.groupId());
        MappingRule rule = MappingRule.forGroup(spec.attrKey(), spec.attrValue(), spec.groupId(), tierGuard.currentTier());
        rules.saveAndFlush(rule); // flush in-scope so RLS WITH CHECK stamps the acting tier
        evaluator.reevaluateRule(rule);
        audit(AuditType.MAPPING_RULE_CREATED, rule);
        return view(rule, groupName);
    }

    @Override
    @Transactional
    public MappingRuleView update(UUID id, MappingRuleSpec spec) {
        MappingRule rule = requireInTier(id);
        String groupName = requireGroupInTier(spec.groupId());
        if (!rule.getGroupId().equals(spec.groupId())) {
            evaluator.retractAll(rule); // clear the OLD group's memberships before repointing
        }
        rule.redefine(spec.attrKey(), spec.attrValue(), spec.groupId());
        rules.saveAndFlush(rule);
        evaluator.reevaluateRule(rule);
        audit(AuditType.MAPPING_RULE_UPDATED, rule);
        return view(rule, groupName);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        MappingRule rule = requireInTier(id);
        evaluator.retractAll(rule); // remove every membership it materialized before dropping the rule
        rules.delete(rule);
        audit(AuditType.MAPPING_RULE_DELETED, rule);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MappingRuleView> list() {
        List<MappingRule> all = rules.findAll();
        Map<UUID, String> names = groups.idNames(all.stream().map(MappingRule::getGroupId).distinct().toList())
                .stream().collect(Collectors.toMap(IdName::getId, IdName::getName)); // one lookup for every group
        return all.stream().map(rule -> view(rule, names.get(rule.getGroupId()))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MappingRuleView get(UUID id) {
        MappingRule rule = requireInTier(id);
        return view(rule, groupName(rule.getGroupId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> preview(MappingRuleSpec spec) {
        return evaluator.matchingUsers(spec.attrKey(), spec.attrValue());
    }

    private MappingRule requireInTier(UUID id) {
        return tierGuard.requireInTier(rules.findById(id), () -> new NotFoundException("mapping rule not found"));
    }

    /** The target group must exist, be in the acting tier, and not be a system group; returns its name. */
    private String requireGroupInTier(UUID groupId) {
        GroupView group = group(groupId).orElseThrow(() -> BadRequestException.of("mapping.rule.groupUnknown"));
        if (!Objects.equals(groups.orgIdOf(groupId).orElse(null), tierGuard.currentTier())) {
            throw BadRequestException.of("mapping.rule.groupNotInTier");
        }
        if (group.system()) {
            throw BadRequestException.of("mapping.rule.groupSystem");
        }
        return group.name();
    }

    private Optional<GroupView> group(UUID groupId) {
        try {
            return Optional.of(groups.get(groupId));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    private String groupName(UUID groupId) {
        return groups.idNames(List.of(groupId)).stream().findFirst().map(IdName::getName).orElse(null);
    }

    private MappingRuleView view(MappingRule rule, String groupName) {
        int assigned = (int) memberships.countByRuleId(rule.getId());
        return new MappingRuleView(rule.getId().toString(), rule.getAttrKey(), rule.getAttrValue(),
                rule.getThenKind(), rule.getGroupId().toString(), groupName, assigned);
    }

    private void audit(AuditType type, MappingRule rule) {
        String principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName() : "system";
        String detail = "%s %s=%s -> group %s".formatted(MappingTargetKind.GROUP, rule.getAttrKey(),
                rule.getAttrValue(), rule.getGroupId());
        audit.record(new AuditRecord(type, principal, true, detail, null,
                AuditSubjectType.GROUP, rule.getGroupId().toString(), rule.getOrgId()));
    }
}
