package com.example.sso.user;

import com.example.sso.shared.IdName;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Directory contract for roles and role membership (RBAC groups), used by admin and SCIM. Returns the
 * public {@link RoleRef}/{@link UserAccount} projections; the {@code Role} entity stays module-internal.
 */
public interface RoleService {

    Optional<RoleRef> findByName(String name);

    /**
     * Resolves a role NAME within a tier: {@code orgId}'s own role of that name first, else the GLOBAL one
     * ({@code orgId} null = global only). Authorization checks on a role name MUST resolve through this
     * with the acting tier — resolving globally while the assignment resolves per-org would let a scoped
     * admin hand out an org role the check never inspected.
     */
    Optional<RoleRef> findByName(String name, UUID orgId);

    Optional<RoleRef> findById(UUID id);

    /**
     * The org that owns a role (null = a global/system role), resolved authoritatively regardless of the
     * caller's RLS context. Empty for an unknown id or a global role — the "no org owns this role" answer a
     * cross-module same-org authorization check needs.
     */
    Optional<UUID> orgIdOf(UUID roleId);

    /** The role's permission names, resolved inside a transaction (safe to read outside one). */
    Set<String> permissionNames(UUID roleId);

    /**
     * The EFFECTIVE permission names of the given roles under the inheritance DAG — the union of what they
     * carry directly AND what they inherit transitively. Used to cap a new child role so it can never carry a
     * permission its parent (apex) role lacks, which would otherwise bleed up into that shared role.
     */
    Set<String> effectivePermissionNames(Collection<UUID> roleIds);

    /** Returns the role, creating it if absent (idempotent). */
    RoleRef getOrCreate(String name);

    /** Returns the role, creating it if absent and ensuring it is flagged as a system role (idempotent). */
    RoleRef getOrCreateSystem(String name);

    /** Creates a new role (caller guards against duplicates / privilege). */
    RoleRef create(String name);

    /**
     * Admin role builder: creates a role composed of the given catalog permissions. Rejects a
     * duplicate name (409) or an unknown permission (400).
     */
    RoleRef create(String name, Set<String> permissionNames);

    /**
     * Admin role builder that also wires the new role BELOW the given parent roles in the inheritance DAG
     * (each parent then inherits the new role's permissions). The parents must be the creator's apex roles
     * so the new role sits strictly beneath the creator — the mechanism behind "a role you create is one
     * you may assign, and never above your own level". Rejects a cycle. Empty parents = a detached root.
     */
    RoleRef create(String name, Set<String> permissionNames, Collection<UUID> parentRoleIds);

    /**
     * Admin role builder: renames and/or replaces the permissions of a role. System roles cannot be
     * renamed; ROLE_ADMIN's permissions are auto-managed and cannot be edited.
     */
    RoleRef updateRole(UUID roleId, String name, Set<String> permissionNames);

    /** Admin role builder: deletes a non-system role (409 for system roles). */
    void deleteRole(UUID roleId);

    void delete(UUID roleId);

    long count();

    List<RoleRef> findAll();

    /** (id, name) for the given role ids — display-name resolution without exposing the entity. */
    List<IdName> idNames(Collection<UUID> ids);

    /** A page of roles (SCIM 1-based startIndex). */
    List<RoleRef> page(long startIndex, int count);

    /** The users assigned this role. */
    List<UserAccount> members(UUID roleId);

    /** Members of several roles at once (one query) — for SCIM group listing. */
    Map<UUID, List<UserAccount>> membersByRoleIds(Set<UUID> roleIds);

    /** Reconciles the role's membership to exactly {@code userIds} (adds/removes user-role links). */
    void setMembers(UUID roleId, Set<UUID> userIds);

    /** Grants the role to a single user (idempotent). 404s if either the role or the user is missing. */
    void addMember(UUID roleId, UUID userId);

    /** Revokes the role from a single user (idempotent). 404s if either the role or the user is missing. */
    void removeMember(UUID roleId, UUID userId);
}
