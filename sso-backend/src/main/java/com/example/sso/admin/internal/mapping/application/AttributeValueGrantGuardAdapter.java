package com.example.sso.admin.internal.mapping.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.mapping.MappingRuleService;
import com.example.sso.mapping.MappingTarget;
import com.example.sso.metadata.AttributeValueGrantGuard;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Admin's implementation of {@link AttributeValueGrantGuard}: bounds a key by what its values confer.
 *
 * <p>The ceiling is {@link AdminAccessPolicy#mayAssignTarget(com.example.sso.mapping.MappingTargetKind,
 * java.util.UUID)} — the same one the manual grant and the group-conferral path enforce, resolved by target
 * ID. It fails closed on an unresolved actor, which is why nothing here checks for one separately.
 *
 * <p>Verdicts are memoized per target: one key is commonly read by several rules pointing at the same role,
 * and a write names several keys at once, so the naive shape re-resolves the same ceiling repeatedly.
 */
@Component
@RequiredArgsConstructor
class AttributeValueGrantGuardAdapter implements AttributeValueGrantGuard {

    private final MappingRuleService rules;
    private final AdminAccessPolicy accessPolicy;

    @Override
    public Set<String> keysBeyondAuthority(Collection<String> attrKeys) {
        return beyond(rules.privilegeTargetsByKey(attrKeys));
    }

    @Override
    public Set<String> keysBeyondAuthorityToRemove(Collection<String> attrKeys) {
        return beyond(rules.privilegeTargetsGrantedByAbsence(attrKeys));
    }

    private Set<String> beyond(Map<String, Set<MappingTarget>> decided) {
        if (decided.isEmpty()) {
            return Set.of();
        }
        Map<MappingTarget, Boolean> verdicts = new HashMap<>();
        Set<String> beyond = new LinkedHashSet<>();
        for (Map.Entry<String, Set<MappingTarget>> entry : decided.entrySet()) {
            if (!mayConferAll(entry.getValue(), verdicts)) {
                beyond.add(entry.getKey());
            }
        }
        return beyond;
    }

    private boolean mayConferAll(Set<MappingTarget> targets, Map<MappingTarget, Boolean> verdicts) {
        return targets.stream().allMatch(target -> verdicts.computeIfAbsent(target,
                each -> accessPolicy.mayAssignTarget(each.kind(), each.targetId())));
    }
}
