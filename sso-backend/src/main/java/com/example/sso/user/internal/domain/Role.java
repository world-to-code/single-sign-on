package com.example.sso.user.internal.domain;
import com.example.sso.shared.domain.AbstractEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.Hibernate;

import com.example.sso.user.RoleRef;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class Role extends AbstractEntity implements RoleRef {

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    // System roles (ROLE_ADMIN, ROLE_USER) cannot be renamed or deleted; ROLE_ADMIN's permissions
    // are auto-managed (self-heal to the full catalog) and therefore not editable via the role builder.
    @Column(nullable = false)
    private boolean system;

    // LAZY: role permissions are needed only when building authorities (login) or in admin role/user
    // views — all within a transaction; default_batch_fetch_size batches them across roles.
    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    public Role(String name) {
        this.name = name;
    }

    /** Marks this role as a protected system role (idempotent; used by seeding). */
    public void markSystem() {
        this.system = true;
    }

    /** Renames the role (callers must guard {@link #isSystem()} roles). */
    public void rename(String newName) {
        this.name = newName;
    }

    public void addPermission(Permission permission) {
        this.permissions.add(permission);
    }

    /** Replaces the role's permission set wholesale (role builder edit). */
    public void replacePermissions(Collection<Permission> newPermissions) {
        this.permissions.clear();
        this.permissions.addAll(newPermissions);
    }

    /** Read-only view (overrides Lombok's @Getter); mutate via {@link #addPermission}. */
    public Set<Permission> getPermissions() {
        return Collections.unmodifiableSet(permissions);
    }

    @Override
    public Set<String> getPermissionNames() {
        return permissions.stream().map(Permission::getName).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Equality by the natural key ({@code name}), made Hibernate-proxy-safe: {@link Hibernate#getClass}
     * unwraps a lazy proxy for the type check, and the {@code getName()} getter (not the raw field)
     * forces proxy initialization. {@code hashCode} is a stable per-type constant so a rename never
     * strands the entity in a hash-based collection.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        Role other = (Role) o;
        return getName() != null && getName().equals(other.getName());
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
