package com.example.sso.resource.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One directed DAG edge (row of {@code resource_edge}): {@code childId} is a direct child of
 * {@code parentId}. Modelled as an explicit entity (composite key over the two endpoints) so the
 * service layer inserts/deletes edges as visible rows rather than through a hidden {@code @ManyToMany}
 * collection. Self-loops are rejected upstream (the graph reachability check) and by a DB CHECK.
 */
@Entity
@Table(name = "resource_edge")
@IdClass(ResourceEdgeId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class ResourceEdge {

    @Id
    @Column(name = "parent_id", nullable = false)
    private UUID parentId;

    @Id
    @Column(name = "child_id", nullable = false)
    private UUID childId;

    public ResourceEdge(UUID parentId, UUID childId) {
        this.parentId = parentId;
        this.childId = childId;
    }
}
