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

    /** Enabled login policies directly assigned to the given user (matched at the join table). */
    @Query("select distinct p from AuthPolicy p left join fetch p.steps s left join fetch s.factors "
            + "join p.userAssignments ua where p.enabled = true and p.signOnRules.appliesToLogin = true and ua.userId = :userId")
    List<AuthPolicy> findEnabledAssignedToUser(@Param("userId") UUID userId);

    /** Enabled login policies assigned to any of the given roles (matched at the join table). */
    @Query("select distinct p from AuthPolicy p left join fetch p.steps s left join fetch s.factors "
            + "join p.roleAssignments ra where p.enabled = true and p.signOnRules.appliesToLogin = true and ra.roleId in :roleIds")
    List<AuthPolicy> findEnabledAssignedToAnyRole(@Param("roleIds") Collection<UUID> roleIds);

    /** Enabled login policies with NO user and NO role assignment — they apply to every user (incl. Default). */
    @Query("select distinct p from AuthPolicy p left join fetch p.steps s left join fetch s.factors "
            + "where p.enabled = true and p.signOnRules.appliesToLogin = true and p.userAssignments is empty and p.roleAssignments is empty")
    List<AuthPolicy> findEnabledGlobal();

    /** The given policies with steps + factors fetched (for per-app step-up resolution, read detached). */
    @Query("select distinct p from AuthPolicy p left join fetch p.steps s left join fetch s.factors "
            + "where p.id in :ids")
    List<AuthPolicy> findAllByIdFetchingSteps(@Param("ids") Collection<UUID> ids);
}
