package com.example.sso.user.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit access to the {@code app_user_role} join table (user↔role assignments). */
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    @Query("select ur.id.roleId from UserRole ur where ur.id.userId = :userId")
    List<UUID> findRoleIdsByUserId(@Param("userId") UUID userId);

    @Query("select ur.id.userId from UserRole ur where ur.id.roleId = :roleId")
    List<UUID> findUserIdsByRoleId(@Param("roleId") UUID roleId);

    /** All assignment rows for the given users — one query to resolve many users' roles at once. */
    @Query("select ur from UserRole ur where ur.id.userId in :userIds")
    List<UserRole> findByUserIdIn(@Param("userIds") Collection<UUID> userIds);

    /** All assignment rows for the given roles — one query to resolve many roles' members at once. */
    @Query("select ur from UserRole ur where ur.id.roleId in :roleIds")
    List<UserRole> findByRoleIdIn(@Param("roleIds") Collection<UUID> roleIds);

    /** True when the user holds a role with this name (any tier). */
    @Query("select count(ur) > 0 from UserRole ur join Role r on r.id = ur.id.roleId "
            + "where ur.id.userId = :userId and r.name = :name")
    boolean existsByUserIdAndRoleName(@Param("userId") UUID userId, @Param("name") String name);

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
