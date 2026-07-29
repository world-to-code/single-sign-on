package com.example.sso.user.internal.rbac.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** GROUP/ROLE-level deny rows, keyed by subject id. Reads project to the raw {@code pattern} strings. */
public interface PrincipalPermissionDenyRepository extends JpaRepository<PrincipalPermissionDeny, UUID> {

    /**
     * Deny patterns on {@code subjectIds}, scoped to the RESOLVING user's tier. The read runs as platform (RLS
     * off), so this {@code org_id} predicate is the ONLY tenant scoping: a tenant's principal deny ({@code org_id
     * = its org}) applies only to that org's members, while a platform veto ({@code org_id} NULL) is absolute.
     * Without it a tenant could deny a permission on a GLOBAL role's id (readable by every tenant) and strip it
     * from ANOTHER tenant's holders of that role — a cross-tenant denial-of-authority.
     */
    @Query("select d.pattern from PrincipalPermissionDeny d "
            + "where d.subjectType = :subjectType and d.subjectId in :subjectIds "
            + "and (d.orgId is null or d.orgId = :userOrg)")
    Set<String> findPatterns(@Param("subjectType") DenySubjectType subjectType,
            @Param("subjectIds") Collection<UUID> subjectIds, @Param("userOrg") UUID userOrg);

    /** The deny rows (id + pattern) authored on ONE role/group subject, for the console to list and lift. */
    List<PrincipalPermissionDeny> findBySubjectTypeAndSubjectId(DenySubjectType subjectType, UUID subjectId);

    /** The same rows for a whole SET of subjects, so a caller judging several does not read them one at a time. */
    List<PrincipalPermissionDeny> findBySubjectTypeAndSubjectIdIn(DenySubjectType subjectType,
            Collection<UUID> subjectIds);

    @Modifying
    @Query(nativeQuery = true, value = "insert into principal_permission_deny "
            + "(id, subject_type, subject_id, org_id, pattern, created_by, writer_apex_role_id) "
            + "values (gen_random_uuid(), :subjectType, :subjectId, :orgId, :pattern, :createdBy, :apexRoleId) "
            + "on conflict do nothing")
    int insertIfAbsent(@Param("subjectType") String subjectType, @Param("subjectId") UUID subjectId,
            @Param("orgId") UUID orgId, @Param("pattern") String pattern, @Param("createdBy") UUID createdBy,
            @Param("apexRoleId") UUID apexRoleId);

    @Query("select d.id from PrincipalPermissionDeny d "
            + "where d.subjectType = :subjectType and d.subjectId = :subjectId and d.pattern = :pattern")
    Optional<UUID> findId(@Param("subjectType") DenySubjectType subjectType, @Param("subjectId") UUID subjectId,
            @Param("pattern") String pattern);
}
