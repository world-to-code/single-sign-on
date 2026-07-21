package com.example.sso.user.internal.group.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit access to the {@code group_role} join table (group→role delegations). */
public interface UserGroupRoleRepository extends JpaRepository<UserGroupRole, UserGroupRoleId> {

    @Query("select gr.id.roleId from UserGroupRole gr where gr.id.groupId = :groupId")
    List<UUID> findRoleIdsByGroupId(@Param("groupId") UUID groupId);

    /**
     * Which roles each of these groups delegates, in ONE query.
     *
     * <p>For a bulk decision — deciding whether an actor may put members into a set of groups asks this of
     * every group at once, and the per-group finder above would make that a query per group.
     */
    @Query("""
            select gr.id.groupId as groupId, r.name as roleName
            from UserGroupRole gr, Role r
            where r.id = gr.id.roleId and gr.id.groupId in :groupIds
            """)
    List<GroupRoleName> findRoleNamesByGroupIds(@Param("groupIds") Collection<UUID> groupIds);

    @Modifying
    @Query("delete from UserGroupRole gr where gr.id.groupId = :groupId and gr.id.roleId = :roleId")
    void deleteByGroupIdAndRoleId(@Param("groupId") UUID groupId, @Param("roleId") UUID roleId);

    @Modifying
    @Query("delete from UserGroupRole gr where gr.id.groupId = :groupId")
    void deleteByGroupId(@Param("groupId") UUID groupId);

    @Modifying
    @Query("delete from UserGroupRole gr where gr.id.roleId = :roleId")
    void deleteByRoleId(@Param("roleId") UUID roleId);
}
