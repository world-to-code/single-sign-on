package com.example.sso.resource.internal.domain;

import com.example.sso.resource.internal.graph.application.ResourceGraphService;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An organizational unit (table {@code resource}) in the resource DAG. The edges ({@code resource_edge}),
 * leaf members ({@code resource_member}) and delegation grants ({@code resource_role}) are EXPLICIT
 * child entities the service reads/writes directly — this aggregate holds only the node's own columns.
 * It still owns the member-kind invariants of its {@link ResourceType} as pure validation methods that
 * take the type's allowed kinds as an argument; the GLOBAL cycle check needs graph reachability and is
 * enforced by {@code ResourceGraphService} before an edge is inserted. No setters — mutate via behavior.
 */
@Entity
@Table(name = "resource")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class Resource extends AuditedEntity implements OrgOwned {

    @Column(nullable = false, length = 200)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id")
    private ResourceType type;

    /** Owning tenant, or {@code null} for a GLOBAL/platform resource (RLS-isolated per {@code V56}). A child
     *  resource, edge, member and grant all inherit this org from their owning resource. Fixed at creation. */
    @Column(name = "org_id")
    private UUID orgId;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Resource(String name, ResourceType type, UUID orgId) {
        this.name = name;
        this.type = type;
        this.orgId = orgId;
    }

    public void rename(String name) {
        this.name = name;
    }

    /**
     * Validates that a child resource may be nested under this one: this type must allow {@code RESOURCE}
     * members. Callers pass the type's allowed kinds (loaded explicitly) and must have run the global
     * cycle check ({@code ResourceGraphService.attachChild}) first.
     */
    public void requireCanNest(Set<MemberType> allowedMemberTypes) {
        requireAllowed(MemberType.RESOURCE, allowedMemberTypes);
    }

    /** Validates that a leaf member of the given kind may be attached, per this type's allowed kinds. */
    public void requireCanAttachMember(MemberType memberType, Set<MemberType> allowedMemberTypes) {
        if (memberType == MemberType.RESOURCE) {
            throw BadRequestException.of("resource.member.childNotMember");
        }
        requireAllowed(memberType, allowedMemberTypes);
    }

    private void requireAllowed(MemberType memberType, Set<MemberType> allowedMemberTypes) {
        if (!allowedMemberTypes.contains(memberType)) {
            throw BadRequestException.of("resource.member.typeNotAllowed", type.getName(), memberType);
        }
    }
}
