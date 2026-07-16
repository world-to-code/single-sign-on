package com.example.sso.mapping.internal.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MappingRuleRepository extends JpaRepository<MappingRule, UUID> {

    /** Rules pointing at a target id (RLS-scoped) — for cleanup when the target group/role is deleted. */
    List<MappingRule> findByTargetId(UUID targetId);

    /**
     * The rule under a {@code PESSIMISTIC_WRITE} row lock (empty if it was deleted concurrently). Serializes a
     * delete/retract against a materialize: whichever path locks the rule first runs to completion before the
     * other proceeds, so a materialize can never insert a provenance row + grant that a concurrent delete then
     * orphans (the provenance cascades on rule delete, but the target grant would otherwise linger untracked).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MappingRule r where r.id = :id")
    Optional<MappingRule> findByIdForUpdate(@Param("id") UUID id);
}
