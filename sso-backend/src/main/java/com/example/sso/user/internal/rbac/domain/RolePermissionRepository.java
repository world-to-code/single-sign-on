package com.example.sso.user.internal.rbac.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit access to the {@code role_permission} join table (role→permission grants). */
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {

    @Query("select rp.id.permissionId from RolePermission rp where rp.id.roleId = :roleId")
    List<UUID> findPermissionIdsByRoleId(@Param("roleId") UUID roleId);

    /** All grant rows for the given roles — one query to resolve many roles' permissions at once. */
    @Query("select rp from RolePermission rp where rp.id.roleId in :roleIds")
    List<RolePermission> findByRoleIdIn(@Param("roleIds") Collection<UUID> roleIds);

    @Modifying
    @Query("delete from RolePermission rp "
            + "where rp.id.roleId = :roleId and rp.id.permissionId = :permissionId")
    void deleteByRoleIdAndPermissionId(@Param("roleId") UUID roleId,
            @Param("permissionId") UUID permissionId);

    @Modifying
    @Query("delete from RolePermission rp where rp.id.roleId = :roleId")
    void deleteByRoleId(@Param("roleId") UUID roleId);
}
