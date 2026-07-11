package com.example.sso.user.internal.application;

import com.example.sso.user.internal.rbac.domain.Permission;
import com.example.sso.user.internal.rbac.domain.PermissionRepository;
import com.example.sso.user.internal.rbac.domain.RolePermission;
import com.example.sso.user.internal.rbac.domain.RolePermissionRepository;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the EFFECTIVE permission names of a set of held roles under the inheritance DAG: the union of
 * the permissions those roles carry AND the permissions of every role they transitively inherit. This is
 * the sole channel through which inheritance affects authority — it contributes permission NAMES only,
 * never a role name, so an inherited (descendant) role's NAME can never become an authority (which would
 * open a second authority-propagation path, e.g. resource-subtree tier-admin — see
 * {@code SsoUserDetailsService.roleAuthorities}).
 *
 * <p>The closure and the {@code role_permission} read both run inside the caller's transaction and are
 * RLS-confined, so a tenant login sees only its own + global edges/grants. One CTE + one batched grant
 * read + one batched name read — no per-role query.
 */
@Component
@RequiredArgsConstructor
class RoleInheritanceResolver {

    private final RoleClosure roleClosure;
    private final RolePermissionRepository rolePermissions;
    private final PermissionRepository permissions;

    Set<String> effectivePermissionNames(Collection<UUID> heldRoleIds) {
        Set<UUID> closure = roleClosure.descendantsAndSelf(heldRoleIds);
        if (closure.isEmpty()) {
            return Set.of();
        }
        Set<UUID> permissionIds = rolePermissions.findByRoleIdIn(closure).stream()
                .map(RolePermission::getPermissionId).collect(Collectors.toSet());
        if (permissionIds.isEmpty()) {
            return Set.of();
        }
        return permissions.findAllById(permissionIds).stream()
                .map(Permission::getName).collect(Collectors.toSet());
    }
}
