package com.example.sso.user;

import com.example.sso.shared.IdName;
import com.example.sso.shared.Page;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Write/read path for organizational groups: admin CRUD plus membership management. Groups are a
 * directory concern, SEPARATE from RBAC roles and the policy/app assignment subsystems. Returns the
 * public {@link GroupView} projection; the backing entity stays module-internal.
 */
public interface UserGroupService {

    List<GroupView> listAll();

    /** A DB-paged slice of all groups (0-based page), ordered by name — for the admin directory. */
    Page<GroupView> listAll(int page, int size);

    /** A DB-paged slice of the groups whose id is in {@code ids} — for a scoped admin's directory. */
    Page<GroupView> listByIds(Collection<UUID> ids, int page, int size);

    GroupView get(UUID id);

    /** A page of a group's members (id, username), ordered by username. */
    GroupMembersPage members(UUID id, int page, int size);

    /** Typeahead group search for the assignment picker. */
    List<Suggestion> search(String q, int limit);

    GroupView create(GroupSpec spec);

    GroupView update(UUID id, GroupSpec spec);

    void delete(UUID id);

    GroupView setMembers(UUID id, Set<UUID> memberIds);

    /** Replaces the roles delegated to the group; every member inherits them. Unknown role → 400. */
    GroupView setRoles(UUID id, Set<String> roleNames);

    /** The groups the given user belongs to, each with the roles that group delegates. */
    List<GroupMembership> membershipsForUser(UUID userId);

    /** Ids of all users who are members of ANY of the given groups (bulk scope expansion). */
    Set<UUID> memberIdsOf(Collection<UUID> groupIds);

    /** (id, name) labels for the given group ids — resolve display names without loading groups. */
    List<IdName> idNames(Collection<UUID> ids);

    /** Ids of the groups the user belongs to — no role/detail loading (authorization hot path). */
    Set<UUID> groupIdsOf(UUID userId);

    /**
     * The org that administers the group, for authorization only: present ONLY when the group exists AND is
     * org-owned. A global (org_id null) or unknown group yields empty — no tenant admin administers it — so a
     * tenant admin can never reach a global/foreign group through an org-scope check.
     */
    Optional<UUID> orgIdOf(UUID groupId);
}
