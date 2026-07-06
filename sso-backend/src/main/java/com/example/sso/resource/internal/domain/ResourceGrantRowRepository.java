package com.example.sso.resource.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/** Explicit access to delegation grants ({@code resource_role}); all grant reads/writes go here. */
public interface ResourceGrantRowRepository extends JpaRepository<ResourceGrantRow, ResourceGrantRowId> {

    List<ResourceGrantRow> findByResourceId(UUID resourceId);

    List<ResourceGrantRow> findByResourceIdIn(Collection<UUID> resourceIds);

    @Modifying
    @Transactional
    void deleteByResourceIdAndUserIdAndTier(UUID resourceId, UUID userId, ResourceRoleTier tier);

    /** Drops every grant on a resource — used when deleting the resource. */
    @Modifying
    @Transactional
    void deleteByResourceId(UUID resourceId);
}
