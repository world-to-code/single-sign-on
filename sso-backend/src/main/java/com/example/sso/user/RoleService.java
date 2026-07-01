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

    Optional<RoleRef> findById(UUID id);

    /** Returns the role, creating it if absent (idempotent). */
    RoleRef getOrCreate(String name);

    /** Creates a new role (caller guards against duplicates / privilege). */
    RoleRef create(String name);

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
}
