package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.ActingAdminTier;
import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.LastAdminGuard;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.shared.Page;
import com.example.sso.user.role.Roles;
import com.example.sso.user.account.Suggestion;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.account.UserUpdate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Transactional
    public AdminUserView setUserPermissions(UUID id, Set<String> permissionNames) {
        AdminUserView view = AdminUserView.of(userService.setDirectPermissions(id, permissionNames));
        auditLogger.log(AuditType.USER_PERMISSIONS_UPDATED, AuditSubjectType.USER, id.toString(),
                "user=" + id + " permissions=" + permissionNames);
        return view;
    }
}
