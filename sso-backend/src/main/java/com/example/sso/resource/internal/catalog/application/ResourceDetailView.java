package com.example.sso.resource.internal.catalog.application;

import java.util.List;

/**
 * Full admin detail of a resource node for the scoped console: its parents and children (for DAG
 * navigation) and its members/grants with display labels resolved. Assembled in
 * {@code ResourceAdminService.detail} inside the loading transaction.
 */
public record ResourceDetailView(String id, String name, String typeName,
                                 List<ResourceNodeView> parents, List<ResourceNodeView> children,
                                 List<ResourceMemberDetailView> members, List<ResourceGrantDetailView> grants) {
}
