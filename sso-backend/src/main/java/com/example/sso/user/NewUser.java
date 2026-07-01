package com.example.sso.user;

import java.util.Set;

/**
 * Immutable parameter object for {@link UserService#createUser(NewUser)}: the identity attributes for
 * a new user, the raw (unencoded) password, and the directly-assigned role names.
 */
public record NewUser(String username, String email, String displayName,
                      String rawPassword, Set<String> roleNames) {
}
