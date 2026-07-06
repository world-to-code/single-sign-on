package com.example.sso.organization.internal.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, UUID> {

    boolean existsByOrgIdAndUserId(UUID orgId, UUID userId);

    long countByOrgId(UUID orgId);

    void deleteByOrgIdAndUserId(UUID orgId, UUID userId);

    @Query("select m.orgId from OrganizationMembership m where m.userId = :userId")
    List<UUID> findOrgIdsByUserId(@Param("userId") UUID userId);

    @Query("select m.userId from OrganizationMembership m where m.orgId = :orgId")
    List<UUID> findUserIdsByOrgId(@Param("orgId") UUID orgId);
}
