package com.example.sso.authpolicy.internal.domain;

import com.example.sso.shared.IdName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthPolicyRepository extends JpaRepository<AuthPolicy, UUID> {

    /** Name lookup within the GLOBAL tier (org_id IS NULL) — the seeded Default and platform policies. */
    Optional<AuthPolicy> findByNameAndOrgIdIsNull(String name);

    /** Name lookup within one tenant's tier — used to reject a duplicate name inside the same org. */
    Optional<AuthPolicy> findByNameAndOrgId(String name, UUID orgId);

    /** Policies at a given priority in the GLOBAL tier — used to keep priority unique within the tier. */
    List<AuthPolicy> findByPriorityAndOrgIdIsNull(int priority);

    /** Policies at a given priority in one tenant's tier — used to keep priority unique within the tier. */
    List<AuthPolicy> findByPriorityAndOrgId(int priority, UUID orgId);

    /**
     * The resolved policy is read (steps + their allowed factors) AFTER the resolve transaction by the
     * non-transactional login flow, so the finders below fetch-join {@code steps} (a {@code List}) and
     * each step's {@code factors} (a {@code Set}) to initialize them before the entity detaches. The
     * assignment sets stay LAZY — they are only matched in the query, never read off the detached entity.
     * Scoped to the GLOBAL tier: the Default is a global policy, and a tenant may legitimately name a
     * policy "Default" too (partial-unique per org), so this must resolve the one global row deterministically.
     */
    @Query("select distinct p from AuthPolicy p left join fetch p.steps s left join fetch s.factors "
            + "where p.name = :name and p.orgId is null")
    Optional<AuthPolicy> findByNameFetchingSteps(@Param("name") String name);

    /** (id, name) of every policy — for name lookups without loading the step/assignment graphs. */
    @Query("select p.id as id, p.name as name from AuthPolicy p")
    List<IdName> findIdNames();

    List<AuthPolicy> findAllByOrderByPriorityDesc();

    /** The given policies with steps + factors fetched (for per-app step-up resolution, read detached). */
    @Query("select distinct p from AuthPolicy p left join fetch p.steps s left join fetch s.factors "
            + "where p.id in :ids")
    List<AuthPolicy> findAllByIdFetchingSteps(@Param("ids") Collection<UUID> ids);
}
