package com.example.sso.resource.internal.application;

import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.ResourceType;
import java.util.Collection;
import java.util.List;

/** Admin view of a resource type and the member kinds it allows. */
public record ResourceTypeView(String id, String name, List<String> allowedMemberTypes) {

    public static ResourceTypeView of(ResourceType type, Collection<MemberType> allowedMemberTypes) {
        return new ResourceTypeView(type.getId().toString(), type.getName(),
                allowedMemberTypes.stream().map(MemberType::name).sorted().toList());
    }
}
