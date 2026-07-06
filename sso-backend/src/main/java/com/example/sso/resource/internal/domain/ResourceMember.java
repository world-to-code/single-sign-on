package com.example.sso.resource.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.UUID;

/**
 * A polymorphic leaf member value object (type + id): the cohesive, behavior-carrying core of a
 * {@link ResourceMemberRow}, embedded inside its composite id. {@code memberId} is text so it can hold
 * both uuids (users/groups) and application ids (OIDC client / SAML RP), mirroring
 * {@code app_assignment.app_id}. The factories and the compact-constructor UUID check keep the value
 * bounded — a malformed group/user id is rejected here, not deep in the authorization ports.
 */
@Embeddable
public record ResourceMember(
        @Enumerated(EnumType.STRING) @Column(name = "member_type", nullable = false, length = 20) MemberType memberType,
        @Column(name = "member_id", nullable = false, length = 255) String memberId) {

    public ResourceMember {
        // GROUP/USER member ids must be uuids — the authorization ports parse them back with
        // UUID.fromString, so reject malformed ids at the boundary instead of failing there.
        if (memberType == MemberType.GROUP || memberType == MemberType.USER) {
            UUID.fromString(memberId);
        }
    }

    public static ResourceMember group(UUID groupId) {
        return new ResourceMember(MemberType.GROUP, groupId.toString());
    }

    public static ResourceMember user(UUID userId) {
        return new ResourceMember(MemberType.USER, userId.toString());
    }

    public static ResourceMember application(String appId) {
        return new ResourceMember(MemberType.APPLICATION, appId);
    }
}
