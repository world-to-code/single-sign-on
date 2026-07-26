package com.example.sso.user.internal.rbac.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** ORG-level deny rows. A tenant's own denies key on its {@code orgId}; the platform veto is {@code orgId IS NULL}. */
public interface OrgPermissionDenyRepository extends JpaRepository<OrgPermissionDeny, UUID> {

    @Query("select d.pattern from OrgPermissionDeny d where d.orgId = :orgId")
    Set<String> findPatternsByOrg(@Param("orgId") UUID orgId);

    /** An org's OWN deny rows (id + pattern), for the console to list and lift. Excludes the platform veto. */
    List<OrgPermissionDeny> findByOrgId(UUID orgId);

    /** The PLATFORM veto: denies with no org, absolute across every tenant. */
    @Query("select d.pattern from OrgPermissionDeny d where d.orgId is null")
    Set<String> findPlatformPatterns();

    @Modifying
    @Query(nativeQuery = true, value = "insert into org_permission_deny "
            + "(id, org_id, pattern, created_by, writer_apex_role_id) "
            + "values (gen_random_uuid(), :orgId, :pattern, :createdBy, :apexRoleId) on conflict do nothing")
    int insertIfAbsent(@Param("orgId") UUID orgId, @Param("pattern") String pattern,
            @Param("createdBy") UUID createdBy, @Param("apexRoleId") UUID apexRoleId);

    /** {@code is not distinct from} so a NULL orgId (platform veto) matches by-value, not the never-true {@code = NULL}. */
    @Query(nativeQuery = true, value = "select id from org_permission_deny "
            + "where pattern = :pattern and org_id is not distinct from :orgId")
    Optional<UUID> findId(@Param("orgId") UUID orgId, @Param("pattern") String pattern);
}
