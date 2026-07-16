package com.example.sso.user.internal.application;

import com.example.sso.authpolicy.factor.Factors;

import com.example.sso.shared.IdName;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.rbac.PermissionGrantPolicy;
import com.example.sso.user.role.Roles;
import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import com.example.sso.user.internal.rbac.domain.Permission;
import com.example.sso.user.internal.rbac.domain.PermissionRepository;
import com.example.sso.user.internal.role.domain.Role;
import com.example.sso.user.internal.rbac.domain.RolePermission;
import com.example.sso.user.internal.rbac.domain.RolePermissionRepository;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.internal.role.domain.RoleRepository;
import com.example.sso.user.internal.group.domain.UserGroupRepository;
import com.example.sso.user.internal.group.domain.UserGroupRoleRepository;
import com.example.sso.user.internal.role.domain.UserRole;
import com.example.sso.user.internal.role.domain.UserRoleRepository;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleDeletedEvent;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.account.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link RoleService}: roles and role membership (RBAC groups) for admin and SCIM. Returns the
 * {@link RoleRef}/{@link UserAccount} projections; role-permission grants and user-role links are managed
 * here as EXPLICIT join-table inserts/deletes (no JPA cascade), so callers never touch the entities.
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
    // Also reserved: the well-known SYSTEM role names. Their names ARE emitted as authorities (globally, and
    // for an org's provisioned copies), so a tenant-created role squatting one of these names would be
    // indistinguishable from the real thing to any authorization check that keys on the name.
    private static final Set<String> RESERVED_AUTHORITY_NAMES = Set.of("MFA_COMPLETE", Roles.SCIM,
            Roles.ADMIN, Roles.USER, Roles.GROUP_ADMIN, Roles.ORG_ADMIN);
    private static final Set<String> RESERVED_AUTHORITY_PREFIXES = Set.of("FACTOR_", "AUTH_TIME_", "STEPUP_TIME_");

    private final RoleRepository roles;
    private final AppUserRepository users;
    private final PermissionRepository permissions;
    private final RolePermissionRepository rolePermissions;
    private final RoleHierarchyWriter roleHierarchyWriter;
    private final RoleInheritanceResolver inheritanceResolver;
    private final UserRoleRepository userRoles;
    private final UserGroupRoleRepository userGroupRoles;
    private final UserGroupRepository groups;
    private final AccessChangePublisher accessChanges;
    private final PermissionGrantPolicy grantPolicy;
    private final OrgContext orgContext;
    private final RbacHydrator hydrator;
    private final ApplicationEventPublisher events;
    private final RoleClosure roleClosure;
    private final RoleTierResolver tierResolver;

    /** The org a newly-created role belongs to: the active tenant context, or null (global/system role). */
    private UUID creationOrg() {
        return orgContext.currentOrg().orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoleRef> findByName(String name) {
        // Name-based lookup targets the global tier; tenant roles are addressed by id.
        return roles.findByNameAndOrgIdIsNull(name).map(hydrator::hydrateRole).map(r -> r);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoleRef> findByName(String name, UUID orgId) {
        return tierResolver.resolve(name, orgId).map(hydrator::hydrateRole).map(r -> r);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoleRef> findById(UUID id) {
        return roles.findById(id).map(hydrator::hydrateRole).map(r -> r);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> orgIdOf(UUID roleId) {
        // Authoritative (RLS-bypassing) org lookup for cross-module same-org checks: Optional#map drops a null
        // org, so a global/system role and an unknown id both yield empty — exactly "no org owns this role".
        return orgContext.callAsPlatform(() -> roles.findById(roleId).map(Role::getOrgId));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> effectivePermissionNames(Collection<UUID> roleIds) {
        return inheritanceResolver.effectivePermissionNames(roleIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> permissionNames(UUID roleId) {
        // The permission names are read from role_permission (explicit join) inside this tx so callers
        // (e.g. @adminAccessPolicy SpEL, which runs outside the method tx) get a fully-materialized set.
        return roles.findById(roleId)
                .map(role -> Set.copyOf(hydrator.hydrateRole(role).getPermissionNames()))
                .orElse(Set.of());
    }

    @Override
    @Transactional
    public RoleRef getOrCreate(String name) {
        // A name-addressed role is a global one (SCIM group→role, etc.); tenant roles are created via the id path.
        return hydrator.hydrateRole(roles.findByNameAndOrgIdIsNull(name).orElseGet(() -> roles.save(new Role(name))));
    }

    @Override
    @Transactional
    public RoleRef getOrCreateSystem(String name) {
        Role role = roles.findByNameAndOrgIdIsNull(name).orElseGet(() -> new Role(name)); // system roles are global
        if (!role.isSystem()) {
            role.markSystem();
        }

        return hydrator.hydrateRole(roles.save(role));
    }

    @Override
    @Transactional
    public RoleRef create(String name) {
        validateRoleName(name);
        return hydrator.hydrateRole(roles.save(new Role(name, creationOrg())));
    }

    @Override
    @Transactional
    public RoleRef create(String name, Set<String> permissionNames) {
        return create(name, permissionNames, Set.of());
    }

    @Override
    @Transactional
    public RoleRef create(String name, Set<String> permissionNames, Collection<UUID> parentRoleIds) {
        validateRoleName(name);
        UUID org = creationOrg();
        // Unique within its own tier only (a tenant may reuse a name another tenant — or the global tier —
        // already uses; names are not restricted). Escalation via a name like ROLE_ADMIN is prevented at the
        // authority layer: org roles contribute only their PERMISSIONS, never their name as a role authority.
        boolean nameTaken = org == null
                ? roles.findByNameAndOrgIdIsNull(name).isPresent()
                : roles.findByNameAndOrgId(name, org).isPresent();
        if (nameTaken) {
            throw ConflictException.of("user.role.duplicate", name);
        }

        // Resolve (and validate) permissions BEFORE persisting the role, so an unknown/forbidden
        // permission never leaves a half-created role behind.
        Set<Permission> resolved = resolvePermissions(permissionNames, org);
        Role role = roles.save(new Role(name, org));
        grantPermissions(role.getId(), resolved);
        attachBelow(role.getId(), parentRoleIds, org);

        return hydrator.hydrateRole(role);
    }

    /** Wires the brand-new role as a child of each parent in the inheritance DAG (idempotent, cycle-guarded). */
    private void attachBelow(UUID childRoleId, Collection<UUID> parentRoleIds, UUID orgId) {
        parentRoleIds.forEach(parent -> roleHierarchyWriter.link(parent, childRoleId, orgId));
    }

    @Override
    @Transactional
    public RoleRef updateRole(UUID roleId, String name, Set<String> permissionNames) {
        Role role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role not found"));
        if (ADMIN_ROLE.equals(role.getName())) {
            throw ConflictException.of("user.role.adminImmutable");
        }

        if (!role.getName().equals(name)) {
            if (role.isSystem()) {
                throw ConflictException.of("user.role.systemNoRename", role.getName());
            }
            validateRoleName(name);
            boolean taken = role.getOrgId() == null
                    ? roles.findByNameAndOrgIdIsNull(name).isPresent()
                    : roles.findByNameAndOrgId(name, role.getOrgId()).isPresent();
            if (taken) {
                throw ConflictException.of("user.role.duplicate", name);
            }
            role.rename(name);
            roles.save(role);
        }

        replacePermissions(roleId, resolvePermissions(permissionNames, role.getOrgId()));

        // A rename changes the emitted role-name authority and a permission edit changes granted permissions,
        // so end every AFFECTED holder's sessions to shed the now-stale authorities — the role's own holders
        // AND, since a parent inherits this role's permissions, the same-tier ancestor roles' holders.
        accessChanges.forUserIds(holdersAffectedByChange(roleId));
        return hydrator.hydrateRole(role);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> childRoleIds(UUID parentRoleId) {
        return roleClosure.childrenOf(parentRoleId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> parentRoleIds(UUID childRoleId) {
        return roleClosure.parentsOf(childRoleId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UUID> orgIdsByIds(Collection<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> orgIds = new HashMap<>();
        for (Object[] row : roles.findOrgIdsByIds(roleIds)) {
            orgIds.put((UUID) row[0], (UUID) row[1]); // orgId (row[1]) is null for a global role — HashMap allows it
        }
        return orgIds;
    }

    @Override
    @Transactional
    public void setInheritsFrom(UUID parentRoleId, Set<UUID> newChildIds) {
        Role role = roles.findById(parentRoleId).orElseThrow(() -> new NotFoundException("role not found"));
        UUID org = role.getOrgId();
        Set<UUID> current = roleClosure.childrenOf(parentRoleId);
        newChildIds.stream().filter(child -> !current.contains(child))
                .forEach(child -> roleHierarchyWriter.link(parentRoleId, child, org)); // cycle-guarded, RLS-scoped
        current.stream().filter(child -> !newChildIds.contains(child))
                .forEach(child -> roleHierarchyWriter.unlink(parentRoleId, child));
        // Editing this role's CHILDREN changes its (and its ancestors') effective permissions but not its
        // membership nor its ancestor set, so the affected holders are the same before and after — end the
        // sessions of this role's holders AND its same-tier ancestors' holders so none keeps stale authorities.
        accessChanges.forUserIds(holdersAffectedByChange(parentRoleId));
    }

    @Override
    @Transactional
    public void deleteRole(UUID roleId) {
        Role role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role not found"));
        if (role.isSystem()) {
            throw ConflictException.of("user.role.systemNoDelete", role.getName());
        }

        Set<UUID> affected = holdersAffectedByChange(roleId); // resolve before the delete removes the edges
        deleteJoinRows(roleId);
        roles.delete(role);
        events.publishEvent(new RoleDeletedEvent(roleId));
        accessChanges.forUserIds(affected);
    }

    @Override
    @Transactional
    public void delete(UUID roleId) {
        roles.findById(roleId).ifPresent(role -> {
            Set<UUID> affected = holdersAffectedByChange(roleId);
            deleteJoinRows(roleId);
            roles.delete(role);
            events.publishEvent(new RoleDeletedEvent(roleId));
            accessChanges.forUserIds(affected);
        });
    }

    /**
     * Explicitly removes every join row referencing the role before it is deleted: its permission grants
     * ({@code role_permission}), direct user assignments ({@code app_user_role}), group delegations
     * ({@code group_role}) and inheritance edges ({@code role_hierarchy}). The DB has ON DELETE CASCADE FKs
     * too, but the deletes are spelled out here so the code — not the schema — documents exactly what
     * disappears with a role.
     */
    private void deleteJoinRows(UUID roleId) {
        rolePermissions.deleteByRoleId(roleId);
        userRoles.deleteByRoleId(roleId);
        userGroupRoles.deleteByRoleId(roleId);
        roleHierarchyWriter.unlinkRole(roleId);
    }

    /**
     * Rejects (400) a role name that would collide with a security-significant authority, since the
     * role name becomes a granted authority verbatim. Blocks permission-shaped names ({@code a:b}),
     * factor/time authorities, MFA_COMPLETE and the SCIM client role.
     */
    private void validateRoleName(String name) {
        String candidate = name == null ? "" : name.trim();
        boolean reserved = candidate.indexOf(':') >= 0
                || RESERVED_AUTHORITY_NAMES.contains(candidate)
                || RESERVED_AUTHORITY_PREFIXES.stream().anyMatch(candidate::startsWith);

        if (reserved) {
            throw BadRequestException.of("user.role.reservedName", name);
        }
    }

    /**
     * Resolves catalog permission names to (get-or-created) entities; rejects unknown names (400) and any
     * permission not permitted here (403). Two authoritative, caller-independent guards (admin console, SCIM,
     * future):
     * <ul>
     *   <li>a TENANT (org-scoped, {@code roleOrg != null}) role may NEVER carry a platform-only permission —
     *       structurally, regardless of who creates it (a super drilled into that org included), because a
     *       role's permissions are granted verbatim to its holders and a platform permission crosses tenant
     *       boundaries;</li>
     *   <li>a GLOBAL role falls back to the actor policy ({@link PermissionGrantPolicy}) — only an unscoped
     *       super may put a platform permission in a global role.</li>
     * </ul>
     */
    private Set<Permission> resolvePermissions(Set<String> names, UUID roleOrg) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }

        return names.stream().map(name -> {
            if (!CATALOG.contains(name)) {
                throw BadRequestException.of("user.permission.unknown", name);
            }
            boolean permitted = roleOrg != null ? !Permissions.isPlatform(name) : grantPolicy.mayGrant(name);
            if (!permitted) {
                throw new ForbiddenException("not permitted to grant permission: " + name);
            }
            return permissions.findByName(name).orElseGet(() -> permissions.save(new Permission(name)));
        }).collect(Collectors.toSet());
    }

    /** Inserts a {@code role_permission} row per resolved permission (idempotent). */
    private void grantPermissions(UUID roleId, Set<Permission> resolved) {
        resolved.forEach(permission -> rolePermissions.save(new RolePermission(roleId, permission.getId())));
    }

    /** Replaces a role's permission grants: explicitly delete the removed rows and insert the added ones. */
    private void replacePermissions(UUID roleId, Set<Permission> desired) {
        Set<UUID> current = new HashSet<>(rolePermissions.findPermissionIdsByRoleId(roleId));
        Set<UUID> desiredIds = desired.stream().map(Permission::getId).collect(Collectors.toSet());

        current.stream().filter(id -> !desiredIds.contains(id))
                .forEach(id -> rolePermissions.deleteByRoleIdAndPermissionId(roleId, id));
        desired.stream().filter(permission -> !current.contains(permission.getId()))
                .forEach(permission -> rolePermissions.save(new RolePermission(roleId, permission.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return roles.count();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleRef> findAll() {
        return hydrator.hydrateRoles(roles.findAll()).stream().map(RoleRef.class::cast).toList();
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

        return hydrator.hydrateRoles(roles.findAll(PageRequest.of(pageNumber, count)).getContent())
                .stream().map(RoleRef.class::cast).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserAccount> members(UUID roleId) {
        // Member views expose only scalar identity (id/username/…), so no role/permission hydration is needed.
        return users.findAllById(userRoles.findUserIdsByRoleId(roleId)).stream()
                .map(UserAccount.class::cast).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<UserAccount>> membersByRoleIds(Set<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }

        List<UserRole> rows = userRoles.findByRoleIdIn(roleIds);
        Map<UUID, AppUser> byId = users.findAllById(rows.stream().map(UserRole::getUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AppUser::getId, user -> user));

        return rows.stream()
                .filter(row -> byId.containsKey(row.getUserId()))
                .collect(Collectors.groupingBy(UserRole::getRoleId,
                        Collectors.mapping(row -> (UserAccount) byId.get(row.getUserId()), Collectors.toList())));
    }

    @Override
    @Transactional
    public void setMembers(UUID roleId, Set<UUID> userIds) {
        roles.findById(roleId).orElseThrow(() -> new NotFoundException("role not found"));
        Set<UUID> currentIds = new HashSet<>(userRoles.findUserIdsByRoleId(roleId));

        // Explicitly delete the assignments of members no longer wanted...
        Set<UUID> removed = currentIds.stream().filter(id -> !userIds.contains(id)).collect(Collectors.toSet());
        removed.forEach(id -> userRoles.deleteByUserIdAndRoleId(id, roleId));

        // ...and insert one for each newly-selected user that actually exists (unknown ids are dropped).
        Set<UUID> toAdd = userIds.stream().filter(id -> !currentIds.contains(id)).collect(Collectors.toSet());
        users.findAllById(toAdd).forEach(user -> userRoles.save(new UserRole(user.getId(), roleId)));

        // End sessions of everyone whose membership changed (gained or lost the role) to refresh authorities.
        Set<UUID> affected = new HashSet<>(removed);
        affected.addAll(toAdd);
        accessChanges.forUserIds(affected);
    }

    @Override
    @Transactional
    public void addMember(UUID roleId, UUID userId) {
        roles.findById(roleId).orElseThrow(() -> new NotFoundException("role not found"));
        AppUser user = users.findById(userId).orElseThrow(() -> new NotFoundException("user not found"));
        userRoles.save(new UserRole(user.getId(), roleId)); // idempotent (composite PK)
        accessChanges.forUserIds(Set.of(userId));
    }

    @Override
    @Transactional
    public void removeMember(UUID roleId, UUID userId) {
        roles.findById(roleId).orElseThrow(() -> new NotFoundException("role not found"));
        AppUser user = users.findById(userId).orElseThrow(() -> new NotFoundException("user not found"));
        userRoles.deleteByUserIdAndRoleId(user.getId(), roleId); // idempotent
        accessChanges.forUserIds(Set.of(userId));
    }

    /**
     * All users who hold the role — directly assigned plus everyone who inherits it through a group. The
     * group-delegated lookup runs as PLATFORM: {@code user_group} is org-scoped (RLS), so editing a GLOBAL
     * role from within one tenant's context would otherwise miss delegating groups in OTHER tenants and fail
     * to terminate their members' sessions. Session revocation on a role change must span every affected org.
     */
    private Set<UUID> holdersOf(UUID roleId) {
        Set<UUID> ids = new HashSet<>(userRoles.findUserIdsByRoleId(roleId));
        ids.addAll(orgContext.callAsPlatform(() -> groups.findMemberIdsByRoleId(roleId)));
        return ids;
    }

    /**
     * Everyone whose EFFECTIVE authorities change when this role's name/permissions change: the role's own
     * holders PLUS the holders of every ancestor role that inherits it — but only ancestors IN THE SAME TIER.
     * The cross-tier {@code ROLE_ADMIN} is deliberately excluded: it self-heals to the full catalog, so its
     * holders' effective authorities never actually change, and revoking them would needlessly log out every
     * platform super-admin whenever a tenant edits one of its roles.
     */
    private Set<UUID> holdersAffectedByChange(UUID roleId) {
        Set<UUID> affected = holdersOf(roleId);
        UUID tier = orgContext.currentOrg().orElse(null);
        roleClosure.ancestors(Set.of(roleId)).stream()
                .filter(ancestorId -> Objects.equals(roleOrgOf(ancestorId), tier))
                .forEach(ancestorId -> affected.addAll(holdersOf(ancestorId)));
        return affected;
    }

    /** The owning org of a role, resolved RLS-blind (a global/system role = {@code null}). */
    private UUID roleOrgOf(UUID roleId) {
        return orgContext.callAsPlatform(() -> roles.findById(roleId).map(Role::getOrgId).orElse(null));
    }
}
