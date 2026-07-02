package com.example.sso.admin.internal.user.api;

import java.util.Set;

/** Replaces a user's directly-granted permissions. */
public record SetPermissionsRequest(Set<String> permissions) {
}
