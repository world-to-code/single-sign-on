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
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.OwnershipChallenge;
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
    private final ActingAdminTier tier;
    private final MfaService mfaService;
    private final UserGroupService userGroups;
    private final AdminAccessPolicy accessPolicy;
    private final AdminAuditLogger auditLogger;
    private final LastAdminGuard lastAdminGuard;

    @Transactional(readOnly = true)
    public Page<AdminUserView> listUsers(int page, int size) {
        Page<UserAccount> users = tier.administersWholeTier()
                // Tier-scoped: an un-drilled platform admin (tier null) sees ONLY global users; a super-admin
                // drilled into a tenant, or a tenant admin, sees THAT org's users — never all tenants merged.
                ? userService.findByOrg(tier.actingOrg(), page, size)
                : userService.findByIds(accessPolicy.currentManagedUserIds(), page, size); // resource delegate
        return users.map(AdminUserView::of);
    }

    /** Typeahead user search for the assignment picker, scoped to the acting tier (a resource delegate only
     *  the users they manage). */
    @Transactional(readOnly = true)
    public List<Suggestion> searchUsers(String q, int limit) {
        if (tier.administersWholeTier()) {
            return userService.searchUsersInOrg(q, tier.actingOrg(), limit);
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
        if (tier.administersWholeTier()) {
            UUID actingOrg = tier.actingOrg();
            visible.removeIf(id -> !Objects.equals(userService.orgIdOf(id).orElse(null), actingOrg));
        } else {
            visible.retainAll(accessPolicy.currentManagedUserIds());
        }

        return userService.idNames(visible).stream()
                .map(idName -> new Suggestion(idName.getId().toString(), idName.getName()))
                .toList();
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
            throw NotFoundException.of("user.notFound");
        }
        mfaService.resetMfa(id);
        auditLogger.log(AuditType.USER_MFA_RESET, AuditSubjectType.USER, id.toString(), "user=" + id);
    }

    /**
     * Re-sends the proof-of-ownership mail for an unverified address. Recovery, not a grant: the code it mails
     * only flips the verified flag, so it cannot be used to sign in as the user.
     */
    @Transactional
    public void resendEmailVerification(UUID id) {
        if (userService.findById(id).isEmpty()) {
            throw NotFoundException.of("user.notFound");
        }
        userService.requestEmailVerification(id);
        auditLogger.log(AuditType.USER_UPDATED, AuditSubjectType.USER, id.toString(),
                "email verification resent user=" + id);
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
        UserAccount user = userService.findById(id).orElseThrow(() -> NotFoundException.of("user.notFound"));
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
