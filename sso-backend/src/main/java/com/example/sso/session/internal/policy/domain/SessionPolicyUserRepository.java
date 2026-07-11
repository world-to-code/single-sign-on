package com.example.sso.session.internal.policy.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit access to a session policy's user-assignment rows. */
public interface SessionPolicyUserRepository extends JpaRepository<SessionPolicyUser, SessionPolicyUserId> {

    @Query("select u from SessionPolicyUser u where u.id.policyId = :policyId")
    List<SessionPolicyUser> findByPolicyId(@Param("policyId") UUID policyId);

    @Modifying
    @Query("delete from SessionPolicyUser u where u.id.policyId = :policyId")
    void deleteByPolicyId(@Param("policyId") UUID policyId);
}
