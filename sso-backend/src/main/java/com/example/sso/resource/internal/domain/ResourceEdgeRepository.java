package com.example.sso.resource.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/** Explicit access to the DAG edges ({@code resource_edge}); all edge reads/writes go through here. */
public interface ResourceEdgeRepository extends JpaRepository<ResourceEdge, ResourceEdgeId> {

    List<ResourceEdge> findByParentId(UUID parentId);

    List<ResourceEdge> findByParentIdIn(Collection<UUID> parentIds);

    List<ResourceEdge> findByChildId(UUID childId);

    @Modifying
    @Transactional
    void deleteByParentIdAndChildId(UUID parentId, UUID childId);

    /** Drops every edge touching a resource (as parent or as child) — used when deleting the resource. */
    @Modifying
    @Transactional
    void deleteByParentIdOrChildId(UUID parentId, UUID childId);
}
