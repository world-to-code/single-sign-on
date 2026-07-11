package com.example.sso.session.internal.policy.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit access to a session policy's IP-rule rows (also guards network-zone deletion). */
public interface SessionPolicyIpRuleRepository extends JpaRepository<SessionPolicyIpRule, SessionPolicyIpRuleId> {

    @Query("select r from SessionPolicyIpRule r where r.id.policyId = :policyId")
    List<SessionPolicyIpRule> findByPolicyId(@Param("policyId") UUID policyId);

    @Modifying
    @Query("delete from SessionPolicyIpRule r where r.id.policyId = :policyId")
    void deleteByPolicyId(@Param("policyId") UUID policyId);

    /** How many policy IP rules reference the given network zone — guards zone deletion. */
    @Query("select count(r) from SessionPolicyIpRule r where r.id.rule.zoneId = :zoneId")
    long countByZoneId(@Param("zoneId") UUID zoneId);
}
