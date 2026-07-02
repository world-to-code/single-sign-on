package com.example.sso.admin.internal.user.application;

import com.example.sso.user.RoleRef;
import com.example.sso.user.UserAccount;

import java.util.List;

/** Admin view of a user, including role-derived roles and directly-granted permissions. */
public record AdminUserView(String id, String username, String email, String displayName,
                            boolean enabled, List<String> roles, List<String> directPermissions) {

    static AdminUserView of(UserAccount user) {
        return new AdminUserView(user.getId().toString(), user.getUsername(), user.getEmail(),
                user.getDisplayName(), user.isEnabled(),
                user.getRoles().stream().map(RoleRef::getName).sorted().toList(),
                user.getDirectPermissionNames().stream().sorted().toList());
    }
}
