package com.example.sso.user;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Write/read path for organizational groups: admin CRUD plus membership management. Groups are a
 * directory concern, SEPARATE from RBAC roles and the policy/app assignment subsystems. Returns the
 * public {@link GroupView} projection; the backing entity stays module-internal.
 */
public interface UserGroupService {

    List<GroupView> listAll();

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
}
