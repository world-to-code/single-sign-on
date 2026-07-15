package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.LastAdminGuard;
import com.example.sso.organization.OrganizationService;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.mfa.MfaService;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.role.Roles;
import com.example.sso.user.group.GroupMembership;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.account.Suggestion;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.account.UserService;
import com.example.sso.user.account.UserUpdate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
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
 * Admin operations for users and their per-user permissions (RBAC + PBAC). Delegates all user state to
 * the user module (via {@link UserService}) — it never touches the {@code AppUser} entity — and maps the
 * returned projections to the admin API views. Role and role-membership admin lives in
 * {@code RoleAdminService}.
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final String ADMIN_ROLE = Roles.ADMIN;

    private final UserService userService;
    private final MfaService mfaService;
    private final UserGroupService userGroups;
    private final AdminAccessPolicy accessPolicy;
    private final AdminAuditLogger auditLogger;
    private final LastAdminGuard lastAdminGuard;
    private final OrgContext orgContext;
    private final OrganizationService organizations;

    /** The organization (the tenant) a user created by the acting admin belongs to: the org the admin is
     *  drilled into, else null (a platform super-admin's global user). */
    private UUID actingOrg() {
        return orgContext.currentOrg().orElse(null);
    }

    @Transactional(readOnly = true)
    public Page<AdminUserView> listUsers(int page, int size) {
        Page<UserAccount> users = isTierAdmin()
                // Tier-scoped: an un-drilled platform admin (tier null) sees ONLY global users; a super-admin
                // drilled into a tenant, or a tenant admin, sees THAT org's users — never all tenants merged.
                ? userService.findByOrg(actingOrg(), page, size)
                : userService.findByIds(accessPolicy.currentManagedUserIds(), page, size); // resource delegate
        return users.map(AdminUserView::of);
    }

    /** Typeahead user search for the assignment picker, scoped to the acting tier (a resource delegate only
     *  the users they manage). */
    @Transactional(readOnly = true)
    public List<Suggestion> searchUsers(String q, int limit) {
        if (isTierAdmin()) {
            return userService.searchUsersInOrg(q, actingOrg(), limit);
        }
        Set<UUID> managed = accessPolicy.currentManagedUserIds();
        return userService.searchUsers(q, limit).stream()
                .filter(suggestion -> managed.contains(UUID.fromString(suggestion.id())))
                .toList();
    }

    /** Resolves selected user ids to (id, label) chips for the picker, scoped to the acting tier. */
    @Transactional(readOnly = true)
    public List<Suggestion> usersByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        Set<UUID> visible = new HashSet<>(ids);
        if (isTierAdmin()) {
            UUID tier = actingOrg();
            visible.removeIf(id -> !Objects.equals(userService.orgIdOf(id).orElse(null), tier));
        } else {
            visible.retainAll(accessPolicy.currentManagedUserIds());
        }

        return userService.idNames(visible).stream()
                .map(idName -> new Suggestion(idName.getId().toString(), idName.getName()))
                .toList();
    }

    /** A platform super-admin (drilled or not) OR a tenant admin — both scope to their acting tier
     *  ({@link #actingOrg()}); everyone else is a resource-subtree delegate. */
    private boolean isTierAdmin() {
        return accessPolicy.isCurrentActorUnscoped() || accessPolicy.administersBoundOrg();
    }

    @Transactional
    public AdminUserView createUser(NewUser newUser) {
        try {
            UUID org = actingOrg();
            UserAccount user = userService.createUser(newUser, org);
            // Record the org membership too (SCIM and self-signup already do): a user carries a home org_id AND is
            // a member of that org, so every isMember-based check (e.g. resource-admin delegation) sees it.
            if (org != null) {
                organizations.addMember(org, user.getId());
            }
            // An admin-console user is created with a TEMPORARY password the admin chose; require the user to
            // set their own on first login (login completion refuses to finalize until they do).
            if (newUser.rawPassword() != null) {
                userService.requirePasswordReset(user.getId());
            }
            AdminUserView created = AdminUserView.of(user);
            auditLogger.log(AuditType.USER_CREATED, AuditSubjectType.USER, created.id(),
                    "username=" + created.username() + " roles=" + newUser.roleNames());
            return created;
        } catch (IllegalArgumentException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    @Transactional
    public AdminUserView updateUser(UUID id, UserUpdate update) {
        boolean remainsEnabledAdmin = update.enabled()
                && update.roleNames() != null && update.roleNames().contains(ADMIN_ROLE);
        lastAdminGuard.ensureNotLastAdmin(id, remainsEnabledAdmin);
        AdminUserView updated = AdminUserView.of(userService.updateUser(id, update));
        auditLogger.log(AuditType.USER_UPDATED, AuditSubjectType.USER, id.toString(),
                "user=" + id + " enabled=" + update.enabled() + " roles=" + update.roleNames());
        return updated;
    }

    @Transactional
    public AdminUserView setEnabled(UUID id, boolean enabled) {
        lastAdminGuard.ensureNotLastAdmin(id, enabled);
        AdminUserView view = AdminUserView.of(userService.setEnabled(id, enabled));
        auditLogger.log(enabled ? AuditType.USER_ENABLED : AuditType.USER_DISABLED,
                AuditSubjectType.USER, id.toString(), "user=" + id);
        return view;
    }

    @Transactional
    public void deleteUser(UUID id) {
        lastAdminGuard.ensureNotLastAdmin(id, false);
        userService.delete(id);
        auditLogger.log(AuditType.USER_DELETED, AuditSubjectType.USER, id.toString(), "user=" + id);
    }

    /** Clears a user's MFA enrollment so they re-enroll on next login (recovery). */
    @Transactional
    public void resetUserMfa(UUID id) {
        if (userService.findById(id).isEmpty()) {
            throw new NotFoundException("User not found");
        }
        mfaService.resetMfa(id);
        auditLogger.log(AuditType.USER_MFA_RESET, AuditSubjectType.USER, id.toString(), "user=" + id);
    }

    @Transactional
    public AdminUserView setUserPermissions(UUID id, Set<String> permissionNames) {
        AdminUserView view = AdminUserView.of(userService.setDirectPermissions(id, permissionNames));
        auditLogger.log(AuditType.USER_PERMISSIONS_UPDATED, AuditSubjectType.USER, id.toString(),
                "user=" + id + " permissions=" + permissionNames);
        return view;
    }

    /** Full detail for a single user, with roles attributed to their source and effective permissions. */
    @Transactional(readOnly = true)
    public UserDetailView getUser(UUID id) {
        UserAccount user = userService.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        List<GroupMembership> memberships = userGroups.membershipsForUser(id);

        return UserDetailView.of(user, roleAssignments(user, memberships),
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
