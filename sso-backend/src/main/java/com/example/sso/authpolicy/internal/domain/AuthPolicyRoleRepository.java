package com.example.sso.authpolicy.internal.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit lifecycle management of {@link AuthPolicyRole} assignment rows (no JPA cascade from the policy). */
public interface AuthPolicyRoleRepository extends JpaRepository<AuthPolicyRole, AuthPolicyRoleId> {

    /** Remove every role assignment of a policy — the "remove" half of a diff-based reassignment. */
    @Modifying
    @Query("delete from AuthPolicyRole r where r.policy.id = :policyId")
    void deleteByPolicyId(@Param("policyId") UUID policyId);
}
