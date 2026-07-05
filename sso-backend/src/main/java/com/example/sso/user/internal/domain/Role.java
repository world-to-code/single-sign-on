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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class Role extends AbstractEntity implements RoleRef {

    // Uniqueness is tier-aware (partial indexes in V43): global name, or (org_id, name) per tenant —
    // not a single global UNIQUE, so two tenants may name a role the same.
    @Column(nullable = false, length = 64)
    private String name;

    // NULL = a GLOBAL/system role (visible to every tenant); non-null = a custom role owned by that
    // organization (RLS-isolated). Set at creation from the active OrgContext; immutable thereafter.
    @Column(name = "org_id")
    private UUID orgId;

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

    /** A global/system role (no owning org). */
    public Role(String name) {
        this.name = name;
    }

    /** A role owned by {@code orgId} (null = global). The org is fixed at creation. */
    public Role(String name, UUID orgId) {
        this.name = name;
        this.orgId = orgId;
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
     * Equality by the natural key ({@code (orgId, name)} — a role name is unique only within its tenant
     * now, so the owning org is part of identity), made Hibernate-proxy-safe: {@link Hibernate#getClass}
     * unwraps a lazy proxy for the type check, and the getters (not the raw fields) force proxy
     * initialization. {@code hashCode} is a stable per-type constant so a rename/re-scope never strands
     * the entity in a hash-based collection.
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
        return getName() != null && getName().equals(other.getName())
                && Objects.equals(getOrgId(), other.getOrgId());
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
