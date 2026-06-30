package com.example.sso.admin;

import java.util.List;

/** Admin view of a user, including role-derived roles and directly-granted permissions. */
public record AdminUserView(String id, String username, String email, String displayName,
                            boolean enabled, List<String> roles, List<String> directPermissions) {
}
