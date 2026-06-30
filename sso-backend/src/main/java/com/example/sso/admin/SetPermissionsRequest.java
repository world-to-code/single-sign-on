package com.example.sso.admin;

import java.util.Set;

/** Replaces a user's directly-granted permissions. */
public record SetPermissionsRequest(Set<String> permissions) {
}
