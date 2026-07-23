package com.example.sso.user.internal.rbac.domain;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** USER-level deny rows. Reads project to the raw {@code pattern} strings the resolver subtracts. */
public interface UserPermissionDenyRepository extends JpaRepository<UserPermissionDeny, UUID> {

    @Query("select d.pattern from UserPermissionDeny d where d.userId = :userId")
    Set<String> findPatternsByUser(@Param("userId") UUID userId);
}
