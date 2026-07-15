package com.example.sso.mapping.internal.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingRuleRepository extends JpaRepository<MappingRule, UUID> {

    /** Rules pointing at a target id (RLS-scoped) — for cleanup when the target group/role is deleted. */
    List<MappingRule> findByTargetId(UUID targetId);
}
