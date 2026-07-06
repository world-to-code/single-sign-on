package com.example.sso.resource.internal.domain;

import java.io.Serializable;
import java.util.UUID;

/** Composite identifier for {@link ResourceEdge} — the (parent, child) endpoint pair. */
public record ResourceEdgeId(UUID parentId, UUID childId) implements Serializable {
}
