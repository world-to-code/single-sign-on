package com.example.sso.admin.internal.role.application;

import com.example.sso.shared.IdName;
import com.example.sso.user.role.RoleRef;
import java.util.List;
import java.util.Set;

/**
 * Admin detail view of a role: its direct permissions plus the inheritance the flat {@link RoleView} omits —
 * the roles it inherits ({@code inheritsFrom}: each contributes its permissions up into this role) and the
 * resulting {@code effectivePermissions} (direct ∪ inherited). Parents (roles that inherit THIS one) are
 * deliberately NOT exposed — that would reveal roles above the actor (the hide-above rule).
 */
public record RoleDetailView(String id, String name, List<String> permissions, boolean system,
                             List<IdName> inheritsFrom, List<String> effectivePermissions) {

    public static RoleDetailView of(RoleRef role, List<IdName> inheritsFrom, Set<String> effectivePermissions) {
        return new RoleDetailView(role.getId().toString(), role.getName(),
                role.getPermissionNames().stream().sorted().toList(), role.isSystem(),
                inheritsFrom, effectivePermissions.stream().sorted().toList());
    }
}
