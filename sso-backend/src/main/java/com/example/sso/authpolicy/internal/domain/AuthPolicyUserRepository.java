package com.example.sso.authpolicy.internal.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit lifecycle management of {@link AuthPolicyUser} assignment rows (no JPA cascade from the policy). */
public interface AuthPolicyUserRepository extends JpaRepository<AuthPolicyUser, AuthPolicyUserId> {

    /** Remove every user assignment of a policy — the "remove" half of a diff-based reassignment. */
    @Modifying
    @Query("delete from AuthPolicyUser u where u.policy.id = :policyId")
    void deleteByPolicyId(@Param("policyId") UUID policyId);
}
