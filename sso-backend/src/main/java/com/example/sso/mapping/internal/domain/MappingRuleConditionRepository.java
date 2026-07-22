package com.example.sso.mapping.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** RLS-confined access to {@code mapping_rule_condition} (a tenant sees its own + global rows). */
public interface MappingRuleConditionRepository extends JpaRepository<MappingRuleCondition, UUID> {

    List<MappingRuleCondition> findByRuleId(UUID ruleId);

    /**
     * Every condition reading one of these attribute keys — the reverse lookup that answers "who does writing
     * this key decide something for". RLS-confined like the rest.
     */
    List<MappingRuleCondition> findByAttrKeyIn(Collection<String> attrKeys);

    void deleteByRuleId(UUID ruleId);
}
