package com.example.sso.user.group;

import com.example.sso.user.account.Suggestion;

import com.example.sso.shared.IdName;
import com.example.sso.shared.Page;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /** A DB-paged slice of ONE organization's groups (excludes GLOBAL/system groups) — a tenant admin's
     *  directory, which must not surface the platform-wide groups RLS keeps visible for login resolution. */
    Page<GroupView> listByOrg(UUID orgId, int page, int size);

    /**
     * The named groups of one organization as name-to-id, in ONE query.
     *
     * <p>For bulk work that resolves many names at once. Paging the whole directory and matching by hand — the
     * shape this replaces — costs a query per name, materialises every group's full membership list on the way,
     * and silently loses anything past the page ceiling, so a group past it reads as one that does not exist.
     */
    Map<String, UUID> groupIdsByName(Collection<String> names, UUID orgId);

    GroupView get(UUID id);

    /** A page of a group's members (id, username), ordered by username. */
    GroupMembersPage members(UUID id, int page, int size);

    /** Typeahead group search for the assignment picker. */
    List<Suggestion> search(String q, int limit);

    /** Typeahead group search scoped to ONE organization (excludes GLOBAL groups) — a tenant admin's picker. */
    List<Suggestion> searchInOrg(String q, UUID orgId, int limit);

    GroupView create(GroupSpec spec);

    GroupView update(UUID id, GroupSpec spec);

    void delete(UUID id);

    GroupView setMembers(UUID id, Set<UUID> memberIds);

    /**
     * Adds ONE user to a group without disturbing the rest of the membership (unlike {@link #setMembers}); for
     * programmatic membership (e.g. metadata-driven auto-mapping). Same-org and system-group rules still apply;
     * an unknown user is a no-op. Idempotent.
     */
    void addMember(UUID groupId, UUID userId);

    /** Removes ONE user from a group without disturbing the rest of the membership. Idempotent. */
    void removeMember(UUID groupId, UUID userId);

    /**
     * Adds MANY users to a group in one pass (the group and same-org checks resolved once, a single access-changed
     * fan-out) — for bulk programmatic membership like a mapping rule materializing a cohort. Same-org and
     * system-group rules apply; unknown users are dropped. Idempotent.
     */
    void addMembers(UUID groupId, Set<UUID> userIds);

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
