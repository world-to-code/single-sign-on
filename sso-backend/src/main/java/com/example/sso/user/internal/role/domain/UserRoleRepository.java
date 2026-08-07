package com.example.sso.user.internal.role.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit access to the {@code app_user_role} join table (user↔role assignments). */
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    /** Roles this user actually HOLDS at {@code at} — a lapsed grant does not count. */
    @Query("select ur.id.roleId from UserRole ur where ur.id.userId = :userId "
            + "and (ur.expiresAt is null or ur.expiresAt > :at)")
    List<UUID> findRoleIdsHeldAt(@Param("userId") UUID userId, @Param("at") Instant at);

    /** Every assignment on record, lapsed or not. For administering the rows, never for deciding access. */
    @Query("select ur.id.roleId from UserRole ur where ur.id.userId = :userId")
    List<UUID> findAllAssignedRoleIds(@Param("userId") UUID userId);

    /** Who holds this role at {@code at}. */
    @Query("select ur.id.userId from UserRole ur where ur.id.roleId = :roleId "
            + "and (ur.expiresAt is null or ur.expiresAt > :at)")
    List<UUID> findUserIdsHoldingAt(@Param("roleId") UUID roleId, @Param("at") Instant at);

    /** Everyone with an assignment on record, lapsed or not. */
    @Query("select ur.id.userId from UserRole ur where ur.id.roleId = :roleId")
    List<UUID> findAllAssignedUserIds(@Param("roleId") UUID roleId);

    /** All assignment rows for the given users — one query to resolve many users' roles at once. */
    /** The grants that become authorities. Excluding lapsed ones HERE is the enforcement point. */
    @Query("select ur from UserRole ur where ur.id.userId in :userIds "
            + "and (ur.expiresAt is null or ur.expiresAt > :at)")
    List<UserRole> findHeldByUserIdIn(@Param("userIds") Collection<UUID> userIds, @Param("at") Instant at);

    /** All assignment rows for the given roles — one query to resolve many roles' members at once. */
    @Query("select ur from UserRole ur where ur.id.roleId in :roleIds "
            + "and (ur.expiresAt is null or ur.expiresAt > :at)")
    List<UserRole> findHeldByRoleIdIn(@Param("roleIds") Collection<UUID> roleIds, @Param("at") Instant at);

    /** Grants whose time is up: what the sweeper removes, and whose sessions it then ends. */
    @Query("select ur from UserRole ur where ur.expiresAt is not null and ur.expiresAt <= :at")
    List<UserRole> findLapsedBy(@Param("at") Instant at);

    /** True when the user holds a role with this name (any tier). */
    @Query("select count(ur) > 0 from UserRole ur join Role r on r.id = ur.id.roleId "
            + "where ur.id.userId = :userId and r.name = :name "
            + "and (ur.expiresAt is null or ur.expiresAt > :at)")
    boolean holdsRoleNamedAt(@Param("userId") UUID userId, @Param("name") String name, @Param("at") Instant at);

    @Modifying
    @Query("delete from UserRole ur where ur.id.userId = :userId and ur.id.roleId = :roleId")
    void deleteByUserIdAndRoleId(@Param("userId") UUID userId, @Param("roleId") UUID roleId);

    @Modifying
    @Query("delete from UserRole ur where ur.id.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("delete from UserRole ur where ur.id.roleId = :roleId")
    void deleteByRoleId(@Param("roleId") UUID roleId);
}
