package com.example.sso.authpolicy.internal.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit lifecycle management of {@link AuthPolicyStep} rows (no JPA cascade from {@link AuthPolicy}). */
public interface AuthPolicyStepRepository extends JpaRepository<AuthPolicyStep, UUID> {

    /** The policy's steps in {@code step_order}, loaded so the service can delete them (and their factors). */
    @Query("select s from AuthPolicyStep s where s.policy.id = :policyId order by s.stepOrder asc")
    List<AuthPolicyStep> findByPolicyId(@Param("policyId") UUID policyId);
}
