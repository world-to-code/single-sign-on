package com.example.sso.session.internal.policy.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit access to a session policy's role-assignment rows. */
public interface SessionPolicyRoleRepository extends JpaRepository<SessionPolicyRole, SessionPolicyRoleId> {

    @Query("select r from SessionPolicyRole r where r.id.policyId = :policyId")
    List<SessionPolicyRole> findByPolicyId(@Param("policyId") UUID policyId);

    @Modifying
    @Query("delete from SessionPolicyRole r where r.id.policyId = :policyId")
    void deleteByPolicyId(@Param("policyId") UUID policyId);
}
