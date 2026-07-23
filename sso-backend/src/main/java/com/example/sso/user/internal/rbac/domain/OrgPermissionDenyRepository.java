package com.example.sso.user.internal.rbac.domain;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** ORG-level deny rows. A tenant's own denies key on its {@code orgId}; the platform veto is {@code orgId IS NULL}. */
public interface OrgPermissionDenyRepository extends JpaRepository<OrgPermissionDeny, UUID> {

    @Query("select d.pattern from OrgPermissionDeny d where d.orgId = :orgId")
    Set<String> findPatternsByOrg(@Param("orgId") UUID orgId);

    /** The PLATFORM veto: denies with no org, absolute across every tenant. */
    @Query("select d.pattern from OrgPermissionDeny d where d.orgId is null")
    Set<String> findPlatformPatterns();
}
