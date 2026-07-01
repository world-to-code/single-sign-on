package com.example.sso.admin.internal.application;

import java.util.List;

/** Admin view of a role and its permissions (RBAC + PBAC). */
public record RoleView(String id, String name, List<String> permissions) {
}
