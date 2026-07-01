package com.example.sso.admin.internal.application;

import com.example.sso.user.RoleRef;

import java.util.List;

/**
 * Admin view of a role and its permissions (RBAC + PBAC). {@code system} roles (ROLE_ADMIN, ROLE_USER)
 * cannot be renamed or deleted, and ROLE_ADMIN's permissions are auto-managed.
 */
public record RoleView(String id, String name, List<String> permissions, boolean system) {

    static RoleView of(RoleRef role) {
        return new RoleView(role.getId().toString(), role.getName(),
                role.getPermissionNames().stream().sorted().toList(), role.isSystem());
    }
}
