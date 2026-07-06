package com.example.sso.user.internal.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Explicit access to the {@code user_group_member} join table. Reads that must respect a group's
 * tenant visibility (RLS on {@code user_group}) are routed through {@link UserGroupRepository}, which
 * joins the group; the operations here are used once the group itself is already resolved.
 */
public interface UserGroupMemberRepository extends JpaRepository<UserGroupMember, UserGroupMemberId> {

    @Query("select m.id.userId from UserGroupMember m where m.id.groupId = :groupId")
    List<UUID> findUserIdsByGroupId(@Param("groupId") UUID groupId);

    @Modifying
    @Query("delete from UserGroupMember m where m.id.groupId = :groupId and m.id.userId = :userId")
    void deleteByGroupIdAndUserId(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    @Modifying
    @Query("delete from UserGroupMember m where m.id.groupId = :groupId")
    void deleteByGroupId(@Param("groupId") UUID groupId);
}
