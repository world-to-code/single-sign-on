package com.example.sso.admin.internal.role.application;

import com.example.sso.shared.IdName;
import com.example.sso.user.role.RoleRef;
import java.util.List;
import java.util.Set;

/**
 * Admin detail view of a role: its direct permissions plus the inheritance the flat {@link RoleView} omits —
 * the roles it inherits ({@code inheritsFrom}: each contributes its permissions up into this role), the roles
 * that inherit it ({@code inheritedBy}: the direct parents), and the resulting {@code effectivePermissions}
 * (direct ∪ inherited). {@code inheritedBy} is FILTERED to roles the actor may see (same tier, not above them),
 * so it can never reveal a role above the actor — the hide-above rule still holds — and it OMITS the actor's own
 * apex role, which inherits nearly every role by construction and would only be noise on each one.
 */
public record RoleDetailView(String id, String name, List<String> permissions, boolean system,
                             List<IdName> inheritsFrom, List<IdName> inheritedBy,
                             List<String> effectivePermissions) {

    public static RoleDetailView of(RoleRef role, List<IdName> inheritsFrom, List<IdName> inheritedBy,
                                    Set<String> effectivePermissions) {
        return new RoleDetailView(role.getId().toString(), role.getName(),
                role.getPermissionNames().stream().sorted().toList(), role.isSystem(),
                inheritsFrom, inheritedBy, effectivePermissions.stream().sorted().toList());
    }
}
