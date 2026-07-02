package com.example.sso.admin.internal.user.application;

import java.util.List;

/**
 * A role held by a user, with its source: {@code direct} when assigned to the user directly, and/or
 * {@code viaGroups} listing the group(s) that delegate it. Both may be set when a role is granted
 * directly AND inherited through a group.
 */
public record RoleAssignmentView(String roleId, String roleName, boolean direct, List<String> viaGroups) {
}
