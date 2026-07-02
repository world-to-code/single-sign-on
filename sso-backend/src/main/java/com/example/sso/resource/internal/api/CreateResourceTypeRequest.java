package com.example.sso.resource.internal.api;

import com.example.sso.resource.internal.domain.MemberType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import java.util.stream.Collectors;

/** Creates a resource type; {@code allowedMemberTypes} = RESOURCE | GROUP | APPLICATION | USER names. */
public record CreateResourceTypeRequest(@NotBlank String name, @NotEmpty Set<String> allowedMemberTypes) {

    /** The member-kind constraint set; an unknown name is the client's fault (400, not 500). */
    public Set<MemberType> toMemberTypes() {
        return allowedMemberTypes.stream().map(MemberTypes::parse).collect(Collectors.toUnmodifiableSet());
    }
}
