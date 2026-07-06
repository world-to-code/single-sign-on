package com.example.sso.user.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit access to the {@code app_user_permission} join table (direct user permissions). */
public interface UserDirectPermissionRepository
        extends JpaRepository<UserDirectPermission, UserDirectPermissionId> {

    @Query("select udp.id.permissionId from UserDirectPermission udp where udp.id.userId = :userId")
    List<UUID> findPermissionIdsByUserId(@Param("userId") UUID userId);

    /** All direct-permission rows for the given users — one query for many users at once. */
    @Query("select udp from UserDirectPermission udp where udp.id.userId in :userIds")
    List<UserDirectPermission> findByUserIdIn(@Param("userIds") Collection<UUID> userIds);

    @Modifying
    @Query("delete from UserDirectPermission udp "
            + "where udp.id.userId = :userId and udp.id.permissionId = :permissionId")
    void deleteByUserIdAndPermissionId(@Param("userId") UUID userId,
            @Param("permissionId") UUID permissionId);

    @Modifying
    @Query("delete from UserDirectPermission udp where udp.id.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
