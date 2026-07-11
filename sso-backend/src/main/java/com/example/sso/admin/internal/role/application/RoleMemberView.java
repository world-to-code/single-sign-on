package com.example.sso.admin.internal.role.application;

import com.example.sso.user.account.UserAccount;

/** A user who holds a role directly, shown in the role's member list on the role detail page. */
public record RoleMemberView(String id, String username, String displayName, boolean enabled) {

    public static RoleMemberView of(UserAccount user) {
        return new RoleMemberView(user.getId().toString(), user.getUsername(), user.getDisplayName(), user.isEnabled());
    }
}
