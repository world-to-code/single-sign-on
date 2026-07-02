package com.example.sso.resource.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A resource kind (table {@code resource_type}) with member-kind constraints: which
 * {@link MemberType}s a resource of this type may contain — e.g. TEAM → {GROUP, APPLICATION},
 * DEPT → {RESOURCE}. Enforced on attach by the {@link Resource} aggregate.
 */
@Entity
@Table(name = "resource_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class ResourceType extends AuditedEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @ElementCollection
    @CollectionTable(name = "resource_type_allowed_member", joinColumns = @JoinColumn(name = "type_id"))
    @Column(name = "member_type", length = 20)
    @Enumerated(EnumType.STRING)
    private Set<MemberType> allowedMemberTypes = new HashSet<>();

    public ResourceType(String name, Set<MemberType> allowedMemberTypes) {
        this.name = name;
        this.allowedMemberTypes.addAll(allowedMemberTypes);
    }

    /** Whether resources of this type may contain the given member kind. */
    public boolean allows(MemberType memberType) {
        return allowedMemberTypes.contains(memberType);
    }

    // Read-only view (overrides Lombok's @Getter); mutate via behavior methods only.
    public Set<MemberType> getAllowedMemberTypes() {
        return Collections.unmodifiableSet(allowedMemberTypes);
    }
}
