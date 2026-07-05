package com.example.sso.authpolicy.internal.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit lifecycle management of {@link AuthPolicyStepFactor} rows (no JPA cascade from the step). */
public interface AuthPolicyStepFactorRepository extends JpaRepository<AuthPolicyStepFactor, AuthPolicyStepFactorId> {

    /** Remove every factor row of a step — called before the step itself is deleted. */
    @Modifying
    @Query("delete from AuthPolicyStepFactor f where f.step.id = :stepId")
    void deleteByStepId(@Param("stepId") UUID stepId);
}
