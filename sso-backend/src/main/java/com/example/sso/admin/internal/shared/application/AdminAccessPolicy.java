package com.example.sso.admin.internal.shared.application;

import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserGroupService;
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
 */
@Component
@RequiredArgsConstructor
public class AdminAccessPolicy {

    static final String ADMIN_ROLE = Roles.ADMIN;

    /** Roles that grant admin-console reach; only a super admin may assign them. */
    private static final Set<String> PRIVILEGED_ROLES = Set.of(Roles.ADMIN, Roles.GROUP_ADMIN);

    private final UserService userService;
    private final UserGroupService userGroups;

    /**
     * Group scope: whether the acting admin may act on {@code targetId} at all. A super admin
     * ({@code ROLE_ADMIN}) may act on anyone; a scoped admin ({@code ROLE_GROUP_ADMIN} only) may act on
     * themselves and on users who are members of a group they manage. Fails closed on an unresolved actor.
     */
    public boolean canAccessUser(UUID targetId) {
        Optional<UUID> actor = currentUserId();
        if (actor.isEmpty()) {
            return false;
        }

        UUID actorId = actor.get();
        return isSuper(actorId) || actorId.equals(targetId) || userGroups.managesUser(actorId, targetId);
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

    /** Whether the acting admin is unscoped (a super {@code ROLE_ADMIN}); used for list scoping. */
    public boolean currentIsSuperAdmin() {
        return currentUserId().map(this::isSuper).orElse(false);
    }

    /** For a scoped acting admin, the ids of the users they may manage (members of their groups). */
    public Set<UUID> currentManagedUserIds() {
        return currentUserId().map(userGroups::membersManagedBy).orElse(Set.of());
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

    private boolean isSelf(UUID targetId) {
        return currentUserId().map(id -> id.equals(targetId)).orElse(false);
    }

    /** Whether the target holds {@code ROLE_ADMIN} directly. */
    private boolean isAdmin(UUID userId) {
        return userService.hasRole(userId, ADMIN_ROLE);
    }

    /** A super admin is an unscoped {@code ROLE_ADMIN} holder (vs. a scoped ROLE_GROUP_ADMIN). */
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
