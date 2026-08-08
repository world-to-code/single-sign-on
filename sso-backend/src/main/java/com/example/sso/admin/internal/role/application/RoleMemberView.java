package com.example.sso.admin.internal.role.application;

import com.example.sso.user.account.UserAccount;
import java.time.Instant;

/**
 * A user who holds a role directly, shown in the role's member list on the role detail page.
 *
 * @param expiresAt when this holder's grant runs out, or null when it is permanent. Shown because a grant
 *                  that quietly expires next Tuesday is indistinguishable in a list from one that never does,
 *                  and an administrator deciding whether to extend it needs to see which they are looking at.
 */
public record RoleMemberView(String id, String username, String displayName, boolean enabled, Instant expiresAt) {

    public static RoleMemberView of(UserAccount user, Instant expiresAt) {
        return new RoleMemberView(user.getId().toString(), user.getUsername(), user.getDisplayName(),
                user.isEnabled(), expiresAt);
    }
}
