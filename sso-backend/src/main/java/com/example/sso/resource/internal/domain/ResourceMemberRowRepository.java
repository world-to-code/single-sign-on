package com.example.sso.resource.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Explicit access to leaf memberships ({@code resource_member}); all member reads/writes go here. */
public interface ResourceMemberRowRepository extends JpaRepository<ResourceMemberRow, ResourceMemberRowId> {

    @Query("select m from ResourceMemberRow m where m.id.resourceId = :resourceId")
    List<ResourceMemberRow> findByResourceId(@Param("resourceId") UUID resourceId);

    @Query("select m from ResourceMemberRow m where m.id.resourceId in :resourceIds")
    List<ResourceMemberRow> findByResourceIdIn(@Param("resourceIds") Collection<UUID> resourceIds);

    @Modifying
    @Transactional
    @Query("delete from ResourceMemberRow m where m.id.resourceId = :resourceId "
            + "and m.id.member.memberType = :memberType and m.id.member.memberId = :memberId")
    void deleteMember(@Param("resourceId") UUID resourceId,
                      @Param("memberType") MemberType memberType,
                      @Param("memberId") String memberId);

    /** Drops every membership of a resource — used when deleting the resource. */
    @Modifying
    @Transactional
    @Query("delete from ResourceMemberRow m where m.id.resourceId = :resourceId")
    void deleteByResourceId(@Param("resourceId") UUID resourceId);
}
