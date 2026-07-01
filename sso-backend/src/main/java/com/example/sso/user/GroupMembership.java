package com.example.sso.user;

import java.util.List;
import java.util.UUID;

/**
 * A user's membership in one group, together with the roles that group delegates to its members.
 * Used to attribute a user's inherited roles to their source group(s) on the user detail view.
 */
public record GroupMembership(UUID groupId, String groupName, List<RoleRef> roles) {
}
