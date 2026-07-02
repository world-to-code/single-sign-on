package com.example.sso.resource.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.shared.error.BadRequestException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An organizational unit (table {@code resource}) in the resource DAG: bundles child resources
 * (edges in {@code resource_edge}, multi-parent) plus polymorphic leaf members and delegation
 * grants. The aggregate enforces LOCAL invariants (self-loop, member-kind constraints of its
 * {@link ResourceType}); the GLOBAL cycle check needs graph reachability and is enforced by
 * {@code ResourceGraphService} before an edge is added. No setters — mutate via behavior methods.
 */
@Entity
@Table(name = "resource")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class Resource extends AuditedEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id")
    private ResourceType type;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Direct child resources (DAG edges; a child may have several parents). */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "resource_edge",
            joinColumns = @JoinColumn(name = "parent_id"),
            inverseJoinColumns = @JoinColumn(name = "child_id"))
    private Set<Resource> children = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "resource_member", joinColumns = @JoinColumn(name = "resource_id"))
    private Set<ResourceMember> members = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "resource_role", joinColumns = @JoinColumn(name = "resource_id"))
    private Set<ResourceGrant> grants = new HashSet<>();

    public Resource(String name, ResourceType type) {
        this.name = name;
        this.type = type;
    }

    public void rename(String name) {
        this.name = name;
    }

    /**
     * Attaches a direct child (a {@code resource_edge}). Enforces the local invariants only — callers
     * must have run the global cycle check ({@code ResourceGraphService.attachChild}) first.
     */
    public void addChild(Resource child) {
        if (this.equals(child)) {
            throw new BadRequestException("A resource cannot be its own child.");
        }
        requireAllowed(MemberType.RESOURCE);
        this.children.add(child);
    }

    public void removeChild(Resource child) {
        this.children.remove(child);
    }

    /** Attaches a leaf member, enforcing the type's member-kind constraints. */
    public void attachMember(ResourceMember member) {
        if (member.memberType() == MemberType.RESOURCE) {
            throw new BadRequestException("Child resources are attached as edges, not members.");
        }
        requireAllowed(member.memberType());
        this.members.add(member);
    }

    public void detachMember(ResourceMember member) {
        this.members.remove(member);
    }

    /**
     * Grants a delegation tier, replacing any existing grant of the same user+tier. Replacement is
     * explicit because the DB primary key is {@code (resource, user, tier)} while the record's
     * identity also includes {@code roleId} — relying on set semantics would let two rows with the
     * same PK coexist in memory and blow up on flush.
     */
    public void grant(ResourceGrant grant) {
        revoke(grant.userId(), grant.tier());
        this.grants.add(grant);
    }

    public void revoke(UUID userId, ResourceRoleTier tier) {
        this.grants.removeIf(g -> g.userId().equals(userId) && g.tier() == tier);
    }

    private void requireAllowed(MemberType memberType) {
        if (!type.allows(memberType)) {
            throw new BadRequestException(
                    "Resource type '" + type.getName() + "' does not allow " + memberType + " members.");
        }
    }

    // Read-only views (override Lombok's @Getter); mutate via the behavior methods above.

    public Set<Resource> getChildren() {
        return Collections.unmodifiableSet(children);
    }

    public Set<ResourceMember> getMembers() {
        return Collections.unmodifiableSet(members);
    }

    public Set<ResourceGrant> getGrants() {
        return Collections.unmodifiableSet(grants);
    }
}
