package com.example.sso.mapping.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MappingRuleMembershipRepository extends JpaRepository<MappingRuleMembership, UUID> {

    /**
     * Insert the provenance claim for {@code (ruleId, userId)} unless it already exists, returning the number of
     * rows inserted (1 = this call created the claim, 0 = another concurrent re-evaluation already owns it). The
     * {@code ON CONFLICT DO NOTHING} makes materialize idempotent and race-proof: two concurrent reconciles for
     * the same (rule, user) cannot both insert and abort the loser's transaction on the unique constraint. The
     * caller performs the target grant + audit only when this returns 1. {@code id}/{@code created_at} fall to
     * their column defaults; RLS {@code WITH CHECK} still applies to the native insert.
     */
    @Modifying
    @Query(value = """
            INSERT INTO mapping_rule_membership (rule_id, user_id, target_id, org_id)
            VALUES (:ruleId, :userId, :targetId, :orgId)
            ON CONFLICT (rule_id, user_id) DO NOTHING
            """, nativeQuery = true)
    int insertClaimIfAbsent(@Param("ruleId") UUID ruleId, @Param("userId") UUID userId,
            @Param("targetId") UUID targetId, @Param("orgId") UUID orgId);

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
