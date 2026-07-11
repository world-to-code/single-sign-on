package com.example.sso.resource.internal.catalog.application;

import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceEdge;
import com.example.sso.resource.internal.domain.ResourceGrantRow;
import com.example.sso.resource.internal.domain.ResourceMemberRow;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin view of a resource node: its type, direct children (id+name), polymorphic leaf members, and
 * delegation grants — assembled from the explicit edge/member/grant rows the service loads.
 * {@code childNames} maps every child id to its display name.
 */
public record ResourceView(String id, String name, String typeName, List<ResourceChildView> children,
                           List<ResourceMemberView> members, List<ResourceGrantView> grants) {

    public static ResourceView of(Resource resource, List<ResourceEdge> childEdges, Map<UUID, String> childNames,
                                  List<ResourceMemberRow> members, List<ResourceGrantRow> grants) {
        List<ResourceChildView> childViews = childEdges.stream()
                .map(edge -> new ResourceChildView(edge.getChildId().toString(), childNames.get(edge.getChildId())))
                .sorted(Comparator.comparing(ResourceChildView::name))
                .toList();
        List<ResourceMemberView> memberViews = members.stream()
                .map(member -> new ResourceMemberView(member.getMemberType().name(), member.getMemberId()))
                .sorted(Comparator.comparing(ResourceMemberView::memberType).thenComparing(ResourceMemberView::memberId))
                .toList();
        List<ResourceGrantView> grantViews = grants.stream()
                .map(grant -> new ResourceGrantView(grant.getUserId().toString(), grant.getTier().name()))
                .sorted(Comparator.comparing(ResourceGrantView::userId))
                .toList();
        return new ResourceView(resource.getId().toString(), resource.getName(), resource.getType().getName(),
                childViews, memberViews, grantViews);
    }
}
