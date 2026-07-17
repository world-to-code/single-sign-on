package com.example.sso.portal.internal.catalog.application;

import com.example.sso.metadata.AttributePredicate;
import com.example.sso.metadata.AttributePredicateGroup;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding.SubjectType;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingCondition;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingConditionRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Assembles an {@link SubjectType#ATTRIBUTE} binding's rows in {@code policy_binding_condition} into the AND
 * {@link AttributePredicateGroup} it targets — the single read home shared by the resolver (matching + ordering)
 * and the binding writers (reconcile diff, slot lookup). Loads every candidate's conditions in ONE query so no
 * caller re-issues per binding (no N+1).
 */
@Service
@RequiredArgsConstructor
class PolicyBindingConditions {

    private final PolicyBindingConditionRepository conditions;

    /** Each ATTRIBUTE candidate's conditions as a group, in one query. A binding with NO condition rows is absent
     *  from the map — callers treat a group-less ATTRIBUTE binding as matching nothing (fail-closed). */
    Map<UUID, AttributePredicateGroup> groupsOf(Collection<PolicyBinding> candidates) {
        List<UUID> attributeIds = candidates.stream()
                .filter(binding -> binding.getSubjectType() == SubjectType.ATTRIBUTE)
                .map(PolicyBinding::getId).toList();
        if (attributeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<AttributePredicate>> byBinding = new HashMap<>();
        for (PolicyBindingCondition condition : conditions.findByBindingIdIn(attributeIds)) {
            byBinding.computeIfAbsent(condition.getBindingId(), key -> new ArrayList<>()).add(condition.toPredicate());
        }
        Map<UUID, AttributePredicateGroup> groups = new HashMap<>(byBinding.size());
        byBinding.forEach((id, predicates) -> groups.put(id, new AttributePredicateGroup(predicates)));
        return groups;
    }
}
