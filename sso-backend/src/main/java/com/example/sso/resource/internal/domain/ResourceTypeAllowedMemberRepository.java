package com.example.sso.resource.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/** Explicit access to the member-kind constraints of resource types ({@code resource_type_allowed_member}). */
public interface ResourceTypeAllowedMemberRepository
        extends JpaRepository<ResourceTypeAllowedMember, ResourceTypeAllowedMemberId> {

    List<ResourceTypeAllowedMember> findByTypeId(UUID typeId);

    List<ResourceTypeAllowedMember> findByTypeIdIn(Collection<UUID> typeIds);

    /** Drops every member-kind row of a type — used when deleting the (unused) type. */
    @Modifying
    @Transactional
    void deleteByTypeId(UUID typeId);
}
