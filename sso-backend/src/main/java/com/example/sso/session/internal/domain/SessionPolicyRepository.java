package com.example.sso.session.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionPolicyRepository extends JpaRepository<SessionPolicy, UUID> {

    /** Name lookup within the GLOBAL tier (org_id IS NULL) — the seeded Default and platform policies. */
    Optional<SessionPolicy> findByNameAndOrgIdIsNull(String name);

    /** Name lookup within one tenant's tier — used to reject a duplicate name inside the same org. */
    Optional<SessionPolicy> findByNameAndOrgId(String name, UUID orgId);

    List<SessionPolicy> findAllByOrderByPriorityDesc();
}
