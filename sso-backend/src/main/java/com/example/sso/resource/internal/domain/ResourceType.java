package com.example.sso.resource.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A resource kind (table {@code resource_type}). Its member-kind constraints — which
 * {@link MemberType}s a resource of this type may contain — are held as explicit
 * {@link ResourceTypeAllowedMember} rows, read/written by the service rather than a mapped collection.
 */
@Entity
@Table(name = "resource_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class ResourceType extends AuditedEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    public ResourceType(String name) {
        this.name = name;
    }
}
