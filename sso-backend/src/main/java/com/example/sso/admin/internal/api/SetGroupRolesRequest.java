package com.example.sso.admin.internal.api;

import java.util.Set;

/** Replaces the roles delegated to a group. */
public record SetGroupRolesRequest(Set<String> roleNames) {
}
