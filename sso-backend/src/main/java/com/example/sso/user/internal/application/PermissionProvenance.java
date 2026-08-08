package com.example.sso.user.internal.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where each permission came from: the roles that carry it, and the groups that hand those roles over.
 *
 * <p>The two travel together because they are one walk of the same graph. Answering them separately meant
 * resolving the inheritance closure twice per request — and, worse, left room for the two answers to be
 * computed from different held-role sets and quietly disagree about the same user.
 */
record PermissionProvenance(Map<String, Set<String>> rolesByPermission,
                            Map<String, Set<String>> groupsByPermission) {

    static PermissionProvenance none() {
        return new PermissionProvenance(Map.of(), Map.of());
    }

    List<String> rolesFor(String permission) {
        return List.copyOf(rolesByPermission.getOrDefault(permission, Set.of()));
    }

    List<String> groupsFor(String permission) {
        return List.copyOf(groupsByPermission.getOrDefault(permission, Set.of()));
    }
}
