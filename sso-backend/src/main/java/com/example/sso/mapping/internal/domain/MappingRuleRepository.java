package com.example.sso.mapping.internal.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingRuleRepository extends JpaRepository<MappingRule, UUID> {

    /** Rules targeting a group (RLS-scoped) — for cleanup when the group is deleted. */
    List<MappingRule> findByGroupId(UUID groupId);
}
