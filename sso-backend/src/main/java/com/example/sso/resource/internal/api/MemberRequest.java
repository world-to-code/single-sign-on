package com.example.sso.resource.internal.api;

import com.example.sso.resource.internal.domain.MemberType;
import jakarta.validation.constraints.NotBlank;

/** Attaches/detaches one polymorphic leaf member (GROUP | APPLICATION | USER + its id). */
public record MemberRequest(@NotBlank String memberType, @NotBlank String memberId) {

    public MemberType toMemberType() {
        return MemberTypes.parse(memberType);
    }
}
