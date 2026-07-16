package com.example.sso.mapping.internal.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** RLS-confined access to {@code mapping_rule_condition} (a tenant sees its own + global rows). */
public interface MappingRuleConditionRepository extends JpaRepository<MappingRuleCondition, UUID> {

    List<MappingRuleCondition> findByRuleId(UUID ruleId);

    void deleteByRuleId(UUID ruleId);
}
