package com.example.sso.user.internal.application;

import com.example.sso.user.internal.role.domain.RoleHierarchyEdgeRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one place that walks the role-inheritance DAG ({@code role_hierarchy}). Every walk is a single
 * RLS-confined recursive CTE ({@link RoleHierarchyEdgeRepository}), {@code UNION}-deduped so DAG diamonds
 * terminate and no corrupt cycle can loop. Reused by permission-union at login and by the dominance
 * predicate — the graph primitive both build on.
 */
@Component
@RequiredArgsConstructor
class RoleClosure {

    private final RoleHierarchyEdgeRepository edges;

    /** The given roles PLUS all their transitive descendants (the roles each held role inherits). */
    Set<UUID> descendantsAndSelf(Collection<UUID> rootRoleIds) {
        if (rootRoleIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> closure = new HashSet<>(rootRoleIds);
        closure.addAll(edges.descendantRoleIds(rootRoleIds));
        return closure;
    }

    /** Strict transitive descendants of the given roles (the roots themselves excluded). */
    Set<UUID> descendants(Collection<UUID> rootRoleIds) {
        return rootRoleIds.isEmpty() ? Set.of() : new HashSet<>(edges.descendantRoleIds(rootRoleIds));
    }

    /** Strict transitive ancestors of the given roles (the roles themselves excluded). */
    Set<UUID> ancestors(Collection<UUID> roleIds) {
        return roleIds.isEmpty() ? Set.of() : new HashSet<>(edges.ancestorRoleIds(roleIds));
    }
}
