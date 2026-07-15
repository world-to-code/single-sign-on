package com.example.sso.mapping.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingRuleMembershipRepository extends JpaRepository<MappingRuleMembership, UUID> {

    /** Every membership a rule materialized (RLS-scoped) — retract them all when the rule is deleted. */
    List<MappingRuleMembership> findByRuleId(UUID ruleId);

    /** Every rule's claim on a user (RLS-scoped) — the rules a user is already assigned by, in one query. */
    List<MappingRuleMembership> findByUserId(UUID userId);

    /** How many users a rule currently has assigned — the view's count without hydrating the rows. */
    long countByRuleId(UUID ruleId);

    /** This rule's claim on a user, if it materialized one. */
    Optional<MappingRuleMembership> findByRuleIdAndUserId(UUID ruleId, UUID userId);

    /** Every rule's claim on a (user, target) — whether the user may be removed from the target after a retract. */
    List<MappingRuleMembership> findByUserIdAndTargetId(UUID userId, UUID targetId);
}
