package com.example.sso.resource.internal.application;

import com.example.sso.resource.internal.domain.Resource;
import java.util.Comparator;
import java.util.List;

/**
 * Admin view of a resource node: its type, direct children (id+name), polymorphic leaf members, and
 * delegation grants. Projected inside the loading transaction (the collections are LAZY).
 */
public record ResourceView(String id, String name, String typeName, List<ResourceChildView> children,
                           List<ResourceMemberView> members, List<ResourceGrantView> grants) {

    public static ResourceView of(Resource resource) {
        List<ResourceChildView> children = resource.getChildren().stream()
                .map(child -> new ResourceChildView(child.getId().toString(), child.getName()))
                .sorted(Comparator.comparing(ResourceChildView::name))
                .toList();
        List<ResourceMemberView> members = resource.getMembers().stream()
                .map(member -> new ResourceMemberView(member.memberType().name(), member.memberId()))
                .sorted(Comparator.comparing(ResourceMemberView::memberType).thenComparing(ResourceMemberView::memberId))
                .toList();
        List<ResourceGrantView> grants = resource.getGrants().stream()
                .map(grant -> new ResourceGrantView(grant.userId().toString(), grant.tier().name()))
                .sorted(Comparator.comparing(ResourceGrantView::userId))
                .toList();
        return new ResourceView(resource.getId().toString(), resource.getName(), resource.getType().getName(),
                children, members, grants);
    }
}
