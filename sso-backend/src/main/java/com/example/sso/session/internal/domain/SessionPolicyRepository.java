package com.example.sso.session.internal.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionPolicyRepository extends JpaRepository<SessionPolicy, UUID> {

    Optional<SessionPolicy> findByName(String name);

    List<SessionPolicy> findAllByOrderByPriorityDesc();

    /**
     * All policies (priority desc) with the assignment sets AND the IP rules fetch-joined, so the cache can
     * hold them detached. All three are {@code Set}s (not bags), so a single query fetching them is allowed;
     * the small per-policy collections keep the (three-way) Cartesian product negligible.
     */
    @Query("select distinct p from SessionPolicy p "
            + "left join fetch p.assignedUserIds left join fetch p.assignedRoleIds "
            + "left join fetch p.ipRules order by p.priority desc")
    List<SessionPolicy> findAllWithAssignmentsByPriorityDesc();

    /** Enabled policies directly assigned to the given user (matched at the join table). */
    @Query("select distinct p from SessionPolicy p join p.assignedUserIds u where p.enabled = true and u = :userId")
    List<SessionPolicy> findEnabledAssignedToUser(@Param("userId") UUID userId);

    /** Enabled policies assigned to any of the given roles (matched at the join table). */
    @Query("select distinct p from SessionPolicy p join p.assignedRoleIds r where p.enabled = true and r in :roleIds")
    List<SessionPolicy> findEnabledAssignedToAnyRole(@Param("roleIds") Collection<UUID> roleIds);

    /** Enabled policies with NO user and NO role assignment — they apply to every user (incl. Default). */
    @Query("select p from SessionPolicy p where p.enabled = true and p.assignedUserIds is empty and p.assignedRoleIds is empty")
    List<SessionPolicy> findEnabledGlobal();

    /** How many policy IP rules reference the given network zone — guards zone deletion. */
    @Query("select count(r) from SessionPolicy p join p.ipRules r where r.zoneId = :zoneId")
    long countReferencingZone(@Param("zoneId") UUID zoneId);
}
