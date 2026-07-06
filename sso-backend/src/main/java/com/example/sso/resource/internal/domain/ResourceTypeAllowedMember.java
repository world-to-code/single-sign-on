package com.example.sso.resource.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One member-kind a resource type permits (row of {@code resource_type_allowed_member}): a resource of
 * {@code typeId} may contain members of {@code memberType}. An explicit entity over the composite key
 * replaces the former {@code @ElementCollection} on {@code ResourceType}.
 */
@Entity
@Table(name = "resource_type_allowed_member")
@IdClass(ResourceTypeAllowedMemberId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class ResourceTypeAllowedMember {

    @Id
    @Column(name = "type_id", nullable = false)
    private UUID typeId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "member_type", nullable = false, length = 20)
    private MemberType memberType;

    public ResourceTypeAllowedMember(UUID typeId, MemberType memberType) {
        this.typeId = typeId;
        this.memberType = memberType;
    }
}
