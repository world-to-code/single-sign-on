package com.example.sso.admin.internal.shared.application;

import com.example.sso.admin.internal.audit.application.AuditScope;
import com.example.sso.resource.ApplicationAuthorization;
import com.example.sso.resource.GroupAuthorization;
import com.example.sso.resource.ResourceAuthorization;
import com.example.sso.resource.UserAuthorization;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Instance-level (ABAC) authorization for admin user operations, invoked from method-security SpEL
 * (e.g. {@code @PreAuthorize("hasAuthority('user:update') and @adminAccessPolicy.canUpdateUser(...)")})
 * and composed with the static {@code hasAuthority} permission check. These rules depend on the ACTOR
 * relative to the target, which a static permission cannot express:
 * <ul>
 *   <li>an admin cannot disable or delete their own account (self-lockout);</li>
 *   <li>an admin cannot revoke their own {@code ROLE_ADMIN} (self-demotion).</li>
 * </ul>
 * The actor-independent "last administrator" invariant lives in {@link UserAdminService} (a 409, not a
 * 403). When the acting user cannot be resolved the self-checks default to allowing (the operation is
 * still gated by the static permission), so a lookup miss never blocks a legitimate admin.
 *
 * <p>Scope comes entirely from the resource module's subtree ports — a delegate reaches a user/group/app
 * only if their subtree covers it; a super admin bypasses (see {@link #isCurrentActorUnscoped()}).
 */
@Component
@RequiredArgsConstructor
public class AdminAccessPolicy {

    static final String ADMIN_ROLE = Roles.ADMIN;

    /** Roles that grant admin-console reach; only a super admin may assign them. */
    private static final Set<String> PRIVILEGED_ROLES = Set.of(Roles.ADMIN, Roles.GROUP_ADMIN);

    private final UserService userService;
    private final RoleService roleService;
    private final UserAuthorization userAuth;
    private final GroupAuthorization groupAuth;
    private final ApplicationAuthorization appAuth;
    private final ResourceAuthorization resourceAuth;

    /**
     * User scope: whether the acting admin may act on {@code targetId} at all. A super admin
     * ({@code ROLE_ADMIN}) may act on anyone; a scoped admin may act on themselves and on users within
     * their resource subtree (direct USER members or members of a scoped group). Fails closed on an
     * unresolved actor.
     */
    public boolean canAccessUser(UUID targetId) {
        Optional<UUID> actor = currentUserId();
        if (actor.isEmpty()) {
            return false;
        }

        UUID actorId = actor.get();
        return resourceAuth.isUnscoped(actorId)
                || actorId.equals(targetId)
                || userAuth.canManage(actorId, targetId);
    }

    /** Only a super admin may mint new user accounts (a scoped admin manages existing members only). */
    public boolean canCreateUser() {
        return currentUserId().map(this::isSuper).orElse(false);
    }

    /**
     * Only a super admin may assign a privileged role (ROLE_ADMIN/ROLE_GROUP_ADMIN) — e.g. by delegating
     * it to a group. Otherwise a non-super admin with group management could grant admin to a group they
     * belong to and escalate. A set with no privileged role is allowed for anyone.
     */
    public boolean mayAssignRoles(Collection<String> roleNames) {
        return currentIsSuperAdmin() || !containsPrivilegedRole(roleNames);
    }

    /**
     * Whether the acting admin holds {@code ROLE_ADMIN} DIRECTLY — gates super-only privileged grants
     * (role/permission assignment); visibility scoping uses {@link #isCurrentActorUnscoped()} instead.
     */
    public boolean currentIsSuperAdmin() {
        return currentUserId().map(this::isSuper).orElse(false);
    }

    /**
     * Whether the acting admin is a super {@code ROLE_ADMIN} (direct or group-delegated). List filtering
     * must branch on this first: the {@code scoped*}/{@code managed*} sets are empty for a super admin.
     */
    public boolean isCurrentActorUnscoped() {
        return currentUserId().map(resourceAuth::isUnscoped).orElse(false);
    }

    /** Users a scoped admin may manage: those inside their resource subtree. */
    public Set<UUID> currentManagedUserIds() {
        return currentUserId().map(userAuth::scopedUserIds).orElse(Set.of());
    }

    /** Group scope: whether the acting admin may manage {@code groupId} (resource subtree; super bypasses). */
    public boolean canAccessGroup(UUID groupId) {
        return currentUserId().map(actorId -> groupAuth.canManage(actorId, groupId)).orElse(false);
    }

    /** For a scoped acting admin, the ids of the groups inside their resource subtree. */
    public Set<UUID> currentScopedGroupIds() {
        return currentUserId().map(groupAuth::scopedGroupIds).orElse(Set.of());
    }

    /** Application scope: whether the acting admin may manage {@code appId} (resource subtree; super bypasses). */
    public boolean canAccessApp(String appId) {
        return currentUserId().map(actorId -> appAuth.canManage(actorId, appId)).orElse(false);
    }

    /** For a scoped acting admin, the ids of the applications inside their resource subtree. */
    public Set<String> currentScopedAppIds() {
        return currentUserId().map(appAuth::scopedAppIds).orElse(Set.of());
    }

    /**
     * The acting admin's audit visibility: unscoped for a super admin, otherwise their union of scoped
     * user/group/app/resource ids. The admin audit log filters entries through {@link AuditScope#permits}.
     */
    public AuditScope currentAuditScope() {
        Optional<UUID> actor = currentUserId();
        if (actor.isEmpty()) {
            return new AuditScope(false, currentUsername(), Set.of(), Set.of(), Set.of(), Set.of());
        }

        UUID actorId = actor.get();
        if (resourceAuth.isUnscoped(actorId)) {
            return new AuditScope(true, currentUsername(), Set.of(), Set.of(), Set.of(), Set.of());
        }
        return new AuditScope(false, currentUsername(), currentManagedUserIds(),
                groupAuth.scopedGroupIds(actorId), appAuth.scopedAppIds(actorId),
                resourceAuth.managedResourceIds(actorId));
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    /** Blocks disabling one's own account or any administrator's account; enabling is always allowed. */
    public boolean canSetEnabled(UUID targetId, boolean enabled) {
        return enabled || (!isSelf(targetId) && !isAdmin(targetId));
    }

    /** Blocks deleting one's own account or any administrator's account. */
    public boolean canDeleteUser(UUID targetId) {
        return !isSelf(targetId) && !isAdmin(targetId);
    }

    /** Blocks resetting another administrator's MFA (resetting your own is allowed). */
    public boolean canResetMfa(UUID targetId) {
        return isSelf(targetId) || !isAdmin(targetId);
    }

    /**
     * Only a super admin may set direct permissions, and never another administrator's. A scoped admin
     * is blocked outright — otherwise they could self-grant any authority and escalate to super admin.
     */
    public boolean canManagePermissions(UUID targetId) {
        return currentIsSuperAdmin() && (isSelf(targetId) || !isAdmin(targetId));
    }

    /**
     * Blocks disabling any administrator (self or other) via a profile update, and self-revocation of a
     * directly-held {@code ROLE_ADMIN}. Editing another administrator's roles IS allowed, so a rogue
     * admin can still be demoted (removing {@code ROLE_ADMIN}) — otherwise admins would be permanent.
     */
    public boolean canUpdateUser(UUID targetId, boolean enabled, Collection<String> roles) {
        boolean targetIsAdmin = isAdmin(targetId);
        if (!currentIsSuperAdmin() && (targetIsAdmin || containsPrivilegedRole(roles))) {
            // A scoped admin may not touch an administrator account, nor assign a privileged role
            // (which would escalate the target — or themselves — to admin).
            return false;
        }
        if (!enabled && targetIsAdmin) {
            return false; // never disable an administrator through an update
        }
        if (!isSelf(targetId)) {
            return true; // other users, incl. a super admin demoting another admin's roles
        }
        if (!enabled) {
            return false; // would disable own account
        }
        return !targetIsAdmin || containsAdmin(roles); // must keep own admin role
    }

    /**
     * Whether the acting admin may GRANT role {@code roleId} to user {@code userId} from the role's member
     * list. Requires user scope; and — unless a super admin — the role must be non-privileged and the target
     * must not already be an administrator (a scoped admin may never touch admin accounts nor hand out
     * {@code ROLE_ADMIN}/{@code ROLE_GROUP_ADMIN}, which would escalate the target or themselves).
     */
    public boolean canGrantRole(UUID userId, UUID roleId) {
        return canManageRoleMembership(userId, roleName(roleId));
    }

    /**
     * Whether the acting admin may REVOKE role {@code roleId} from user {@code userId}. Same scope/privilege
     * rules as granting, plus an admin may not revoke their OWN {@code ROLE_ADMIN} (self-demotion — another
     * admin must). The actor-independent "last administrator" invariant is a 409 in {@link UserAdminService}.
     */
    public boolean canRevokeRole(UUID userId, UUID roleId) {
        String roleName = roleName(roleId);
        if (isSelf(userId) && ADMIN_ROLE.equals(roleName)) {
            return false;
        }
        return canManageRoleMembership(userId, roleName);
    }

    private boolean canManageRoleMembership(UUID userId, String roleName) {
        if (!currentIsSuperAdmin() && (isAdmin(userId) || PRIVILEGED_ROLES.contains(roleName))) {
            return false;
        }
        return canAccessUser(userId);
    }

    /** The role's name, or {@code null} if it no longer exists (the caller's service then 404s). */
    private String roleName(UUID roleId) {
        return roleService.findById(roleId).map(RoleRef::getName).orElse(null);
    }

    private boolean isSelf(UUID targetId) {
        return currentUserId().map(id -> id.equals(targetId)).orElse(false);
    }

    /** Whether the target holds {@code ROLE_ADMIN} directly. */
    private boolean isAdmin(UUID userId) {
        return userService.hasRole(userId, ADMIN_ROLE);
    }

    /**
     * Whether the actor directly holds {@code ROLE_ADMIN} — the unscoped super-admin authority (a scoped
     * delegate holds {@code ROLE_GROUP_ADMIN}, never this). Same underlying check as {@link #isAdmin};
     * kept as a distinct name for the actor-side intent at privilege-gate call sites.
     */
    private boolean isSuper(UUID userId) {
        return userService.hasRole(userId, ADMIN_ROLE);
    }

    private boolean containsAdmin(Collection<String> roles) {
        return roles != null && roles.contains(ADMIN_ROLE);
    }

    private boolean containsPrivilegedRole(Collection<String> roles) {
        return roles != null && roles.stream().anyMatch(PRIVILEGED_ROLES::contains);
    }

    private Optional<UUID> currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        return userService.findByUsername(authentication.getName())
                .map(UserAccount::getId);
    }
}
