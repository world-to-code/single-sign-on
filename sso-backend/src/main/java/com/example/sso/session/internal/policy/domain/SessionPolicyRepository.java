package com.example.sso.session.internal.policy.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionPolicyRepository extends JpaRepository<SessionPolicy, UUID> {

    /** Name lookup within the GLOBAL tier (org_id IS NULL) — the seeded Default and platform policies. */
    Optional<SessionPolicy> findByNameAndOrgIdIsNull(String name);

    /** Name lookup within one tenant's tier — used to reject a duplicate name inside the same org. */
    Optional<SessionPolicy> findByNameAndOrgId(String name, UUID orgId);

    /** Policies at a given priority in the GLOBAL tier — used to keep priority unique within the tier. */
    List<SessionPolicy> findByPriorityAndOrgIdIsNull(int priority);

    /** Policies at a given priority in one tenant's tier — used to keep priority unique within the tier. */
    List<SessionPolicy> findByPriorityAndOrgId(int priority, UUID orgId);

    List<SessionPolicy> findAllByOrderByPriorityDesc();
}
