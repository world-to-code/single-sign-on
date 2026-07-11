package com.example.sso.user.internal.group.domain;

import com.example.sso.shared.domain.AuditedEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * An organizational group (table {@code user_group}): a directory container that bundles USERS
 * (org/department membership), kept SEPARATE from RBAC {@link Role}s and the policy/app assignment
 * subsystems.
 *
 * <p>Sync-ready: an optional {@code externalId} carries the source id from a future LDAP/SCIM
 * integration. State is never mutated through setters — the entity is created fully-formed via its
 * constructor and changes only through intention-revealing domain methods.
 *
 * <p>Membership ({@code user_group_member}) and delegated roles ({@code group_role}) are NOT mapped
 * here: they live in the explicit {@code UserGroupMember} / {@code UserGroupRole} join entities and are
 * written and read through their repositories in the service layer, so every insert/delete is visible.
 */
@Entity
@Table(name = "user_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class UserGroup extends AuditedEntity {

    /** The system group every user belongs to; auto-managed, cannot be renamed/deleted. */
    public static final String ALL_USERS = "All Users";

    // Tier-aware uniqueness (partial indexes in V45): global name, or (org_id, name) per tenant.
    @Column(nullable = false, length = 120)
    private String name;

    // NULL = a GLOBAL/system group (e.g. "All Users", visible to every tenant); non-null = a custom group
    // owned by that organization (RLS-isolated). Set at creation from the active OrgContext; immutable after.
    @Column(name = "org_id")
    private UUID orgId;

    @Column(length = 255)
    private String description;

    /** System groups are platform-managed (e.g. "All Users") and cannot be edited or deleted. */
    @Column(nullable = false)
    private boolean system = false;

    /** Source id for a future LDAP/SCIM sync (nullable, unique when present). */
    @Column(name = "external_id", length = 255)
    private String externalId;

    /** A global/system group (no owning org). */
    public UserGroup(String name, String description, String externalId) {
        this.name = name;
        this.description = description;
        this.externalId = externalId;
    }

    /** A group owned by {@code orgId} (null = global). The org is fixed at creation. */
    public UserGroup(String name, String description, String externalId, UUID orgId) {
        this(name, description, externalId);
        this.orgId = orgId;
    }

    /** Marks this as a platform-managed system group (auto-managed membership, locked from edits). */
    public void markSystem() {
        this.system = true;
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
}
