package com.example.sso.user.internal.rbac.domain;

import com.example.sso.user.rbac.Permissions;
import com.example.sso.shared.domain.AbstractEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.Hibernate;


/**
 * A fine-grained permission (PBAC), e.g. {@code user:update}. Permissions are granted to
 * roles and surface as authorities for method-level {@code @PreAuthorize} policies.
 */
@Entity
@Table(name = "permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class Permission extends AbstractEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    public Permission(String name) {
        this.name = name;
    }

    /**
     * Equality by the natural key ({@code name}), made Hibernate-proxy-safe: {@link Hibernate#getClass}
     * unwraps a lazy proxy for the type check and the {@code getName()} getter forces initialization.
     * {@code hashCode} is a stable per-type constant.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        Permission other = (Permission) o;
        return getName() != null && getName().equals(other.getName());
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
