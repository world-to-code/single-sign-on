package com.example.sso.user.internal.application;

import com.example.sso.user.internal.domain.AppUser;
import com.example.sso.user.internal.domain.Permission;
import com.example.sso.user.internal.domain.PermissionRepository;
import com.example.sso.user.internal.domain.Role;
import com.example.sso.user.internal.domain.RolePermission;
import com.example.sso.user.internal.domain.RolePermissionRepository;
import com.example.sso.user.internal.domain.RoleRepository;
import com.example.sso.user.internal.domain.UserDirectPermission;
import com.example.sso.user.internal.domain.UserDirectPermissionRepository;
import com.example.sso.user.internal.domain.UserRole;
import com.example.sso.user.internal.domain.UserRoleRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the read-only role/permission views of {@link AppUser}/{@link Role} from the EXPLICIT join
 * tables ({@code app_user_role}, {@code app_user_permission}, {@code role_permission}). This replaces
 * the former lazy {@code @ManyToMany} loading: the service calls it before handing an aggregate out as a
 * {@code UserAccount}/{@code RoleRef}. Queries are batched (one per join table) to avoid N+1 across a page.
 *
 * <p>Roles resolved here are filtered by RLS ({@code role.org_id}) exactly as the old join-fetch was, so a
 * tenant role invisible in the current context is omitted — identical authority/view semantics.
 */
@Component
@RequiredArgsConstructor
class RbacHydrator {

    private final UserRoleRepository userRoles;
    private final UserDirectPermissionRepository userDirectPermissions;
    private final RolePermissionRepository rolePermissions;
    private final RoleRepository roles;
    private final PermissionRepository permissions;

    /** Populates each role's permission-name view from {@code role_permission} (one batched query). */
    List<Role> hydrateRoles(List<Role> toHydrate) {
        if (toHydrate.isEmpty()) {
            return toHydrate;
        }

        List<UUID> roleIds = toHydrate.stream().map(Role::getId).distinct().toList();
        List<RolePermission> rows = rolePermissions.findByRoleIdIn(roleIds);
        Map<UUID, String> permissionName = permissionNames(
                rows.stream().map(RolePermission::getPermissionId).collect(Collectors.toSet()));

        Map<UUID, Set<String>> byRole = new HashMap<>();
        rows.forEach(row -> byRole.computeIfAbsent(row.getRoleId(), k -> new LinkedHashSet<>())
                .add(permissionName.get(row.getPermissionId())));
        toHydrate.forEach(role -> role.hydratePermissionNames(byRole.getOrDefault(role.getId(), Set.of())));

        return toHydrate;
    }

    Role hydrateRole(Role role) {
        hydrateRoles(List.of(role));
        return role;
    }

    /**
     * Populates each user's role view (roles carry their own hydrated permission names) and direct
     * permission-name view. Every join table is read once for the whole batch.
     */
    List<AppUser> hydrateUsers(List<AppUser> users) {
        if (users.isEmpty()) {
            return users;
        }

        List<UUID> userIds = users.stream().map(AppUser::getId).toList();

        List<UserRole> roleRows = userRoles.findByUserIdIn(userIds);
        Map<UUID, Role> roleById = roles.findAllById(
                        roleRows.stream().map(UserRole::getRoleId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Role::getId, role -> role));
        hydrateRoles(new ArrayList<>(roleById.values()));
        Map<UUID, Set<Role>> rolesByUser = new HashMap<>();
        roleRows.forEach(row -> {
            Role role = roleById.get(row.getRoleId()); // absent if RLS-invisible in this context
            if (role != null) {
                rolesByUser.computeIfAbsent(row.getUserId(), k -> new LinkedHashSet<>()).add(role);
            }
        });

        List<UserDirectPermission> permissionRows = userDirectPermissions.findByUserIdIn(userIds);
        Map<UUID, String> permissionName = permissionNames(
                permissionRows.stream().map(UserDirectPermission::getPermissionId).collect(Collectors.toSet()));
        Map<UUID, Set<String>> directByUser = new HashMap<>();
        permissionRows.forEach(row -> directByUser.computeIfAbsent(row.getUserId(), k -> new LinkedHashSet<>())
                .add(permissionName.get(row.getPermissionId())));

        users.forEach(user -> {
            user.hydrateRoles(rolesByUser.getOrDefault(user.getId(), Set.of()));
            user.hydrateDirectPermissionNames(directByUser.getOrDefault(user.getId(), Set.of()));
        });

        return users;
    }

    AppUser hydrateUser(AppUser user) {
        hydrateUsers(List.of(user));
        return user;
    }

    private Map<UUID, String> permissionNames(Collection<UUID> permissionIds) {
        if (permissionIds.isEmpty()) {
            return Map.of();
        }
        return permissions.findAllById(permissionIds).stream()
                .collect(Collectors.toMap(Permission::getId, Permission::getName));
    }
}
