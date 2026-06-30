package com.example.sso.session;

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

    /** Enabled policies directly assigned to the given user (matched at the join table). */
    @Query("select distinct p from SessionPolicy p join p.assignedUserIds u where p.enabled = true and u = :userId")
    List<SessionPolicy> findEnabledAssignedToUser(@Param("userId") UUID userId);

    /** Enabled policies assigned to any of the given roles (matched at the join table). */
    @Query("select distinct p from SessionPolicy p join p.assignedRoleIds r where p.enabled = true and r in :roleIds")
    List<SessionPolicy> findEnabledAssignedToAnyRole(@Param("roleIds") Collection<UUID> roleIds);

    /** Enabled policies with NO user and NO role assignment — they apply to every user (incl. Default). */
    @Query("select p from SessionPolicy p where p.enabled = true and p.assignedUserIds is empty and p.assignedRoleIds is empty")
    List<SessionPolicy> findEnabledGlobal();
}
