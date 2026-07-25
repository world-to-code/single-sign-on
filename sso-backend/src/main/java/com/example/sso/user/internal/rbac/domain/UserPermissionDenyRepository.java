package com.example.sso.user.internal.rbac.domain;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** USER-level deny rows. Reads project to the raw {@code pattern} strings the resolver subtracts. */
public interface UserPermissionDenyRepository extends JpaRepository<UserPermissionDeny, UUID> {

    @Query("select d.pattern from UserPermissionDeny d where d.userId = :userId")
    Set<String> findPatternsByUser(@Param("userId") UUID userId);

    /** Race-proof author: a duplicate (same user+pattern) is a no-op, so a concurrent double-create can't throw. */
    @Modifying
    @Query(nativeQuery = true, value = "insert into app_user_permission_deny "
            + "(id, user_id, org_id, pattern, created_by, writer_apex_role_id) "
            + "values (gen_random_uuid(), :userId, :orgId, :pattern, :createdBy, :apexRoleId) "
            + "on conflict do nothing")
    int insertIfAbsent(@Param("userId") UUID userId, @Param("orgId") UUID orgId, @Param("pattern") String pattern,
            @Param("createdBy") UUID createdBy, @Param("apexRoleId") UUID apexRoleId);

    @Query("select d.id from UserPermissionDeny d where d.userId = :userId and d.pattern = :pattern")
    Optional<UUID> findId(@Param("userId") UUID userId, @Param("pattern") String pattern);
}
