package com.example.sso.user.account;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable parameter object for {@link UserService#updateUser(UUID, UserUpdate)}: an admin full
 * update of a user's profile, enabled state, and (when non-null) the exact role-name set.
 */
public record UserUpdate(String displayName, String email, boolean enabled, Set<String> roleNames) {
}
