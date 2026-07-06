package com.example.sso.resource.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * One polymorphic leaf membership (row of {@code resource_member}): the embedded {@link ResourceMember}
 * value object belongs to {@code resourceId}. An explicit entity over the composite key replaces the
 * former {@code @ElementCollection} so the service inserts/deletes membership rows directly, while the
 * cohesive member value object (with its factories/validation) stays intact inside the id.
 */
@Entity
@Table(name = "resource_member")
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class ResourceMemberRow {

    @EmbeddedId
    private ResourceMemberRowId id;

    /** Owning tenant (= the member's resource org), or {@code null} for a global resource. */
    @Column(name = "org_id")
    private UUID orgId;

    public ResourceMemberRow(UUID resourceId, ResourceMember member, UUID orgId) {
        this.id = new ResourceMemberRowId(resourceId, member);
        this.orgId = orgId;
    }

    public static ResourceMemberRow of(UUID resourceId, ResourceMember member, UUID orgId) {
        return new ResourceMemberRow(resourceId, member, orgId);
    }

    public UUID getResourceId() {
        return id.resourceId();
    }

    public ResourceMember getMember() {
        return id.member();
    }

    public MemberType getMemberType() {
        return id.member().memberType();
    }

    public String getMemberId() {
        return id.member().memberId();
    }
}
