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
     * Which role IDS each of these groups delegates, in ONE query.
     *
     * <p>For a bulk decision — deciding whether an actor may put members into a set of groups asks this of
     * every group at once, and the per-group finder above would make that a query per group.
     *
     * <p>Ids, not names: a name resolves org-first with a global fallback while the delegation points at a
     * stored id, so authorizing by name checks a different role than membership actually confers.
     *
     * <p>{@code UserGroup} is in the from-clause to be FILTERED, not to be read: {@code group_role} carries no
     * RLS policy of its own, so without reaching the group this answered for any id given to it — including a
     * group in another tenant, whose delegated role names the caller then learned. {@code user_group} is under
     * FORCE ROW LEVEL SECURITY, so joining it makes the database do the scoping rather than trusting every
     * caller to have narrowed the ids first. Do not remove the join to tidy the query.
     */
    @Query("""
            select gr.id.groupId as groupId, gr.id.roleId as roleId
            from UserGroupRole gr, UserGroup g
            where g.id = gr.id.groupId and gr.id.groupId in :groupIds
            """)
    List<GroupRoleId> findRoleIdsByGroupIds(@Param("groupIds") Collection<UUID> groupIds);

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
