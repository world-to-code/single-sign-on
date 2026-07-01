package com.example.sso.admin.internal.application;

import com.example.sso.audit.AuditType;
import com.example.sso.mfa.MfaService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.GroupMembership;
import com.example.sso.user.NewUser;
import com.example.sso.user.Permissions;
import com.example.sso.user.RbacService;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.Suggestion;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import com.example.sso.user.UserUpdate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin operations for users, roles, and per-user permissions (RBAC + PBAC). Delegates all user/role
 * state to the user module (via {@link UserService}/{@link RoleService}) — it never touches the
 * {@code AppUser}/{@code Role} entities — and maps the returned projections to the admin API views.
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final UserService userService;
    private final RoleService roleService;
    private final RbacService rbacService;
    private final MfaService mfaService;
    private final UserGroupService userGroups;
    private final AdminAccessPolicy accessPolicy;
    private final AdminAuditLogger auditLogger;

    @Transactional(readOnly = true)
    public List<AdminUserView> listUsers() {
        if (accessPolicy.currentIsSuperAdmin()) {
            return userService.findAll().stream().map(AdminUserView::of).toList();
        }

        Set<UUID> managed = accessPolicy.currentManagedUserIds();
        return userService.findAll().stream()
                .filter(user -> managed.contains(user.getId()))
                .map(AdminUserView::of)
                .toList();
    }

    /** Typeahead user search for the assignment picker (scoped admins see only users they manage). */
    @Transactional(readOnly = true)
    public List<Suggestion> searchUsers(String q, int limit) {
        List<Suggestion> results = userService.searchUsers(q, limit);
        if (accessPolicy.currentIsSuperAdmin()) {
            return results;
        }

        Set<UUID> managed = accessPolicy.currentManagedUserIds();
        return results.stream()
                .filter(suggestion -> managed.contains(UUID.fromString(suggestion.id())))
                .toList();
    }

    @Transactional
    public AdminUserView createUser(CreateUserRequest request) {
        Set<String> roleNames = (request.roles() == null || request.roles().isEmpty())
                ? Set.of("ROLE_USER") : request.roles();

        try {
            AdminUserView created = AdminUserView.of(userService.createUser(new NewUser(request.username(),
                    request.email(), request.displayName(), request.password(), roleNames)));
            auditLogger.log(AuditType.USER_CREATED, "username=" + created.username() + " roles=" + roleNames);
            return created;
        } catch (IllegalArgumentException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    @Transactional
    public AdminUserView updateUser(UUID id, UpdateUserRequest request) {
        boolean remainsEnabledAdmin = request.enabled()
                && request.roles() != null && request.roles().contains(ADMIN_ROLE);
        ensureNotLastAdmin(id, remainsEnabledAdmin);
        AdminUserView updated = AdminUserView.of(userService.updateUser(id, new UserUpdate(request.displayName(),
                request.email(), request.enabled(), request.roles())));
        auditLogger.log(AuditType.USER_UPDATED, "user=" + id + " enabled=" + request.enabled() + " roles=" + request.roles());
        return updated;
    }

    @Transactional
    public AdminUserView setEnabled(UUID id, boolean enabled) {
        ensureNotLastAdmin(id, enabled);
        AdminUserView view = AdminUserView.of(userService.setEnabled(id, enabled));
        auditLogger.log(enabled ? AuditType.USER_ENABLED : AuditType.USER_DISABLED, "user=" + id);
        return view;
    }

    @Transactional
    public void deleteUser(UUID id) {
        ensureNotLastAdmin(id, false);
        userService.delete(id);
        auditLogger.log(AuditType.USER_DELETED, "user=" + id);
    }

    /**
     * Actor-independent invariant: the platform must retain at least one enabled administrator. Rejects
     * (409) an operation that would leave the target as the sole enabled {@code ROLE_ADMIN} holder no
     * longer an enabled admin. {@code remainsEnabledAdmin} is whether the target stays an enabled admin
     * after the operation (then there is nothing to guard).
     */
    private void ensureNotLastAdmin(UUID targetId, boolean remainsEnabledAdmin) {
        if (remainsEnabledAdmin) {
            return;
        }
        RoleRef adminRole = roleService.findByName(ADMIN_ROLE).orElse(null);
        if (adminRole == null) {
            return;
        }

        List<UserAccount> admins = roleService.members(adminRole.getId());
        boolean targetIsEnabledAdmin = admins.stream()
                .anyMatch(user -> user.getId().equals(targetId) && user.isEnabled());
        boolean anotherEnabledAdminExists = admins.stream()
                .anyMatch(user -> user.isEnabled() && !user.getId().equals(targetId));

        if (targetIsEnabledAdmin && !anotherEnabledAdminExists) {
            throw new ConflictException("cannot remove the last administrator");
        }
    }

    /** Clears a user's MFA enrollment so they re-enroll on next login (recovery). */
    @Transactional
    public void resetUserMfa(UUID id) {
        if (userService.findById(id).isEmpty()) {
            throw new NotFoundException("User not found");
        }
        mfaService.resetMfa(id);
        auditLogger.log(AuditType.USER_MFA_RESET, "user=" + id);
    }

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return roleService.findAll().stream().map(RoleView::of).toList();
    }

    @Transactional
    public RoleView createRole(String name, Set<String> permissions) {
        RoleView view = RoleView.of(roleService.create(name, permissions));
        auditLogger.log(AuditType.ROLE_CREATED, "role=" + name + " permissions=" + permissions);
        return view;
    }

    @Transactional
    public RoleView updateRole(UUID id, String name, Set<String> permissions) {
        RoleView view = RoleView.of(roleService.updateRole(id, name, permissions));
        auditLogger.log(AuditType.ROLE_UPDATED, "role=" + id + " name=" + name + " permissions=" + permissions);
        return view;
    }

    @Transactional
    public void deleteRole(UUID id) {
        roleService.deleteRole(id);
        auditLogger.log(AuditType.ROLE_DELETED, "role=" + id);
    }

    @Transactional(readOnly = true)
    public List<PermissionView> listPermissions() {
        return rbacService.allPermissions().stream().map(PermissionView::of).toList();
    }

    @Transactional
    public AdminUserView setUserPermissions(UUID id, Set<String> permissionNames) {
        AdminUserView view = AdminUserView.of(userService.setDirectPermissions(id, permissionNames));
        auditLogger.log(AuditType.USER_PERMISSIONS_UPDATED, "user=" + id + " permissions=" + permissionNames);
        return view;
    }

    /** Full detail for a single user, with roles attributed to their source and effective permissions. */
    @Transactional(readOnly = true)
    public UserDetailView getUser(UUID id) {
        UserAccount user = userService.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        List<GroupMembership> memberships = userGroups.membershipsForUser(id);

        return new UserDetailView(user.getId().toString(), user.getUsername(), user.getEmail(),
                user.getDisplayName(), user.isEnabled(), user.isEmailVerified(), user.isAccountNonLocked(),
                user.getExternalId(), user.getCreatedAt(), user.getUpdatedAt(),
                roleAssignments(user, memberships),
                user.getDirectPermissionNames().stream().sorted().toList(),
                effectivePermissions(user, memberships));
    }

    /** Merges the user's direct roles with roles delegated via groups, tracking each role's source. */
    private List<RoleAssignmentView> roleAssignments(UserAccount user, List<GroupMembership> memberships) {
        Map<UUID, String> names = new LinkedHashMap<>();
        Set<UUID> directIds = new HashSet<>();
        Map<UUID, TreeSet<String>> viaGroups = new LinkedHashMap<>();

        for (RoleRef role : user.getRoles()) {
            names.put(role.getId(), role.getName());
            directIds.add(role.getId());
        }
        for (GroupMembership membership : memberships) {
            for (RoleRef role : membership.roles()) {
                names.putIfAbsent(role.getId(), role.getName());
                viaGroups.computeIfAbsent(role.getId(), k -> new TreeSet<>()).add(membership.groupName());
            }
        }

        List<RoleAssignmentView> assignments = new ArrayList<>();
        names.forEach((roleId, name) -> assignments.add(new RoleAssignmentView(roleId.toString(), name,
                directIds.contains(roleId), List.copyOf(viaGroups.getOrDefault(roleId, new TreeSet<>())))));
        assignments.sort((a, b) -> a.roleName().compareToIgnoreCase(b.roleName()));

        return assignments;
    }

    /** All permissions the user effectively holds: role + group-role + direct, read-implication expanded. */
    private List<String> effectivePermissions(UserAccount user, List<GroupMembership> memberships) {
        Set<String> permissions = new HashSet<>();
        user.getRoles().forEach(role -> permissions.addAll(role.getPermissionNames()));
        memberships.forEach(membership -> membership.roles()
                .forEach(role -> permissions.addAll(role.getPermissionNames())));
        permissions.addAll(user.getDirectPermissionNames());

        return Permissions.expandImplied(permissions).stream().sorted().toList();
    }
}
