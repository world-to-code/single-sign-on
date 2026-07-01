package com.example.sso.user.internal.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * An organizational group (table {@code user_group}): a directory container that bundles USERS
 * (org/department membership), kept SEPARATE from RBAC {@link Role}s and the policy/app assignment
 * subsystems.
 *
 * <p>Sync-ready: an optional {@code externalId} carries the source id from a future LDAP/SCIM
 * integration. State is never mutated through setters — the entity is created fully-formed via its
 * constructor and changes only through intention-revealing domain methods.
 */
@Entity
@Table(name = "user_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class UserGroup {

    /** The system group every user belongs to; auto-managed, cannot be renamed/deleted. */
    public static final String ALL_USERS = "All Users";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(length = 255)
    private String description;

    /** System groups are platform-managed (e.g. "All Users") and cannot be edited or deleted. */
    @Column(nullable = false)
    private boolean system = false;

    /** Source id for a future LDAP/SCIM sync (nullable, unique when present). */
    @Column(name = "external_id", length = 255)
    private String externalId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_group_member", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "user_id")
    private Set<UUID> memberUserIds = new HashSet<>();

    // Roles delegated to the whole group: every member inherits these roles (and their permissions).
    // LAZY: needed only when building a member's authorities or rendering the group/user detail views.
    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "group_role",
            joinColumns = @JoinColumn(name = "group_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UserGroup(String name, String description, String externalId) {
        this.name = name;
        this.description = description;
        this.externalId = externalId;
    }

    /** Marks this as a platform-managed system group (auto-managed membership, locked from edits). */
    public void markSystem() {
        this.system = true;
    }

    /** Adds a single member (used for auto-join into the default group). */
    public void addMember(UUID userId) {
        this.memberUserIds.add(userId);
    }

    public void rename(String name) {
        this.name = name;
    }

    public void describe(String description) {
        this.description = description;
    }

    public void assignExternalId(String externalId) {
        this.externalId = externalId;
    }

    /** Replaces the group's membership wholesale (admin-driven update). */
    public void setMembers(Collection<UUID> memberUserIds) {
        this.memberUserIds.clear();
        this.memberUserIds.addAll(memberUserIds);
    }

    /** Replaces the group's delegated roles wholesale (admin-driven update). */
    public void replaceRoles(Collection<Role> newRoles) {
        this.roles.clear();
        this.roles.addAll(newRoles);
    }

    // Read-only views (override Lombok's @Getter); mutate via the domain methods above.

    public Set<UUID> getMemberUserIds() {
        return Collections.unmodifiableSet(memberUserIds);
    }

    public Set<Role> getRoles() {
        return Collections.unmodifiableSet(roles);
    }
}
