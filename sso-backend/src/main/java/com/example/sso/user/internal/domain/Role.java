package com.example.sso.user.internal.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.example.sso.user.RoleRef;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
@EqualsAndHashCode(of = "name")
public class Role implements RoleRef {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

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

    public void addPermission(Permission permission) {
        this.permissions.add(permission);
    }

    /** Read-only view (overrides Lombok's @Getter); mutate via {@link #addPermission}. */
    public Set<Permission> getPermissions() {
        return Collections.unmodifiableSet(permissions);
    }

    @Override
    public Set<String> getPermissionNames() {
        return permissions.stream().map(Permission::getName).collect(Collectors.toUnmodifiableSet());
    }
}
