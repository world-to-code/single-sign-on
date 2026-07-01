package com.example.sso.authpolicy;

import com.example.sso.shared.IdName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthPolicyRepository extends JpaRepository<AuthPolicy, UUID> {

    Optional<AuthPolicy> findByName(String name);

    /** (id, name) of every policy — for name lookups without loading the EAGER step/assignment graphs. */
    @Query("select p.id as id, p.name as name from AuthPolicy p")
    List<IdName> findIdNames();

    List<AuthPolicy> findAllByOrderByPriorityDesc();

    /** Enabled login policies directly assigned to the given user (matched at the join table). */
    @Query("select distinct p from AuthPolicy p join p.assignedUserIds u where p.enabled = true and p.appliesToLogin = true and u = :userId")
    List<AuthPolicy> findEnabledAssignedToUser(@Param("userId") UUID userId);

    /** Enabled login policies assigned to any of the given roles (matched at the join table). */
    @Query("select distinct p from AuthPolicy p join p.assignedRoleIds r where p.enabled = true and p.appliesToLogin = true and r in :roleIds")
    List<AuthPolicy> findEnabledAssignedToAnyRole(@Param("roleIds") Collection<UUID> roleIds);

    /** Enabled login policies with NO user and NO role assignment — they apply to every user (incl. Default). */
    @Query("select p from AuthPolicy p where p.enabled = true and p.appliesToLogin = true and p.assignedUserIds is empty and p.assignedRoleIds is empty")
    List<AuthPolicy> findEnabledGlobal();
}
