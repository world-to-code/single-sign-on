package com.example.sso.user.internal.application;

import com.example.sso.shared.IdName;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.Roles;
import com.example.sso.user.internal.domain.AppUser;
import com.example.sso.user.internal.domain.AppUserRepository;
import com.example.sso.user.internal.domain.Permission;
import com.example.sso.user.internal.domain.PermissionRepository;
import com.example.sso.user.internal.domain.Role;
import com.example.sso.user.RoleRef;
import com.example.sso.user.internal.domain.RoleRepository;
import com.example.sso.user.Permissions;
import com.example.sso.user.RoleService;
import com.example.sso.user.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link RoleService}: roles and role membership (RBAC groups) for admin and SCIM. Returns the
 * {@link RoleRef}/{@link UserAccount} projections; the user-role link mutation lives here so callers
 * never touch the entities.
 */
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    /** ROLE_ADMIN's permissions are self-healed to the full catalog and thus not editable here. */
    private static final String ADMIN_ROLE = Roles.ADMIN;
    private static final Set<String> CATALOG = Set.copyOf(Permissions.ALL);

    // A role's name is emitted verbatim as a granted authority (see SsoUserDetailsService), so it shares
    // the authority namespace with MFA/factor/permission authorities. Reject any name that would mint a
    // security-significant authority, else a role could grant MFA_COMPLETE, a factor, SCIM access or a
    // permission without going through the proper flow. These mirror authpolicy.Factors / SCIM authorities
    // as protocol constants (the user module must not depend on those modules).
    private static final Set<String> RESERVED_AUTHORITY_NAMES = Set.of("MFA_COMPLETE", Roles.SCIM);
    private static final Set<String> RESERVED_AUTHORITY_PREFIXES = Set.of("FACTOR_", "AUTH_TIME_", "STEPUP_TIME_");

    private final RoleRepository roles;
    private final AppUserRepository users;
    private final PermissionRepository permissions;

    @Override
    @Transactional(readOnly = true)
    public Optional<RoleRef> findByName(String name) {
        return roles.findByName(name).map(r -> r);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoleRef> findById(UUID id) {
        return roles.findById(id).map(r -> r);
    }

    @Override
    @Transactional
    public RoleRef getOrCreate(String name) {
        return roles.findByName(name).orElseGet(() -> roles.save(new Role(name)));
    }

    @Override
    @Transactional
    public RoleRef getOrCreateSystem(String name) {
        Role role = roles.findByName(name).orElseGet(() -> new Role(name));
        if (!role.isSystem()) {
            role.markSystem();
        }

        return roles.save(role);
    }

    @Override
    @Transactional
    public RoleRef create(String name) {
        validateRoleName(name);
        return roles.save(new Role(name));
    }

    @Override
    @Transactional
    public RoleRef create(String name, Set<String> permissionNames) {
        validateRoleName(name);
        roles.findByName(name).ifPresent(existing -> {
            throw new ConflictException("role '" + name + "' already exists");
        });

        Role role = new Role(name);
        role.replacePermissions(resolvePermissions(permissionNames));

        return roles.save(role);
    }

    @Override
    @Transactional
    public RoleRef updateRole(UUID roleId, String name, Set<String> permissionNames) {
        Role role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role not found"));
        if (ADMIN_ROLE.equals(role.getName())) {
            throw new ConflictException("ROLE_ADMIN is managed automatically and cannot be edited");
        }

        if (!role.getName().equals(name)) {
            if (role.isSystem()) {
                throw new ConflictException("system role '" + role.getName() + "' cannot be renamed");
            }
            validateRoleName(name);
            roles.findByName(name).ifPresent(existing -> {
                throw new ConflictException("role '" + name + "' already exists");
            });
            role.rename(name);
        }

        role.replacePermissions(resolvePermissions(permissionNames));

        return role;
    }

    @Override
    @Transactional
    public void deleteRole(UUID roleId) {
        Role role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role not found"));
        if (role.isSystem()) {
            throw new ConflictException("system role '" + role.getName() + "' cannot be deleted");
        }

        roles.delete(role);
    }

    @Override
    @Transactional
    public void delete(UUID roleId) {
        roles.findById(roleId).ifPresent(roles::delete);
    }

    /**
     * Rejects (400) a role name that would collide with a security-significant authority, since the
     * role name becomes a granted authority verbatim. Blocks permission-shaped names ({@code a:b}),
     * factor/time authorities, MFA_COMPLETE and the SCIM client role.
     */
    private static void validateRoleName(String name) {
        String candidate = name == null ? "" : name.trim();
        boolean reserved = candidate.indexOf(':') >= 0
                || RESERVED_AUTHORITY_NAMES.contains(candidate)
                || RESERVED_AUTHORITY_PREFIXES.stream().anyMatch(candidate::startsWith);

        if (reserved) {
            throw new BadRequestException("role name '" + name + "' collides with a reserved authority");
        }
    }

    /** Resolves catalog permission names to (get-or-created) entities; rejects unknown names (400). */
    private Set<Permission> resolvePermissions(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }

        return names.stream().map(name -> {
            if (!CATALOG.contains(name)) {
                throw new BadRequestException("unknown permission: " + name);
            }
            return permissions.findByName(name).orElseGet(() -> permissions.save(new Permission(name)));
        }).collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return roles.count();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleRef> findAll() {
        return roles.findAll().stream().map(RoleRef.class::cast).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdName> idNames(Collection<UUID> ids) {
        return roles.findIdNames(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleRef> page(long startIndex, int count) {
        if (count <= 0) {
            return List.of();
        }

        long zeroBased = Math.max(startIndex - 1, 0);
        int pageNumber = (int) (zeroBased / count);

        return roles.findAll(PageRequest.of(pageNumber, count)).stream().map(RoleRef.class::cast).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserAccount> members(UUID roleId) {
        return users.findByRoles_Id(roleId).stream().map(UserAccount.class::cast).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<UserAccount>> membersByRoleIds(Set<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, List<UserAccount>> byRole = new HashMap<>();
        for (Object[] row : users.findMembersByRoleIdIn(roleIds)) {
            byRole.computeIfAbsent((UUID) row[0], k -> new ArrayList<>()).add((AppUser) row[1]);
        }

        return byRole;
    }

    @Override
    @Transactional
    public void setMembers(UUID roleId, Set<UUID> userIds) {
        Role role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role not found"));
        List<AppUser> current = users.findByRoles_Id(roleId);
        Set<UUID> currentIds = current.stream().map(AppUser::getId).collect(Collectors.toSet());

        // Managed entities inside this @Transactional method — dirty checking flushes; no explicit saves.
        current.stream().filter(u -> !userIds.contains(u.getId())).forEach(u -> u.removeRole(role));
        Set<UUID> toAdd = userIds.stream().filter(id -> !currentIds.contains(id)).collect(Collectors.toSet());
        users.findAllById(toAdd).forEach(u -> u.addRole(role));
    }
}
