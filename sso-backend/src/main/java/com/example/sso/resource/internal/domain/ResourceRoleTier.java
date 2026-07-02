package com.example.sso.resource.internal.domain;

/**
 * Delegation tier on a resource: {@code ADMIN} manages the resource's subtree, {@code VIEWER} gets
 * read-only scope (semantics enforced from Phase 2). A nullable catalog role on the grant reserves
 * resource-scoped RBAC for later.
 */
public enum ResourceRoleTier {
    ADMIN, VIEWER
}
