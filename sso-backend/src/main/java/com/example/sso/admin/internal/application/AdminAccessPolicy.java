package com.example.sso.admin.internal.application;

import com.example.sso.user.UserService;
import java.util.Collection;
import java.util.Optional;
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

    static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final UserService userService;

    /** Blocks disabling one's own account; enabling (or acting on others) is always allowed. */
    public boolean canSetEnabled(UUID targetId, boolean enabled) {
        return enabled || !isSelf(targetId);
    }

    /** Blocks deleting one's own account. */
    public boolean canDeleteUser(UUID targetId) {
        return !isSelf(targetId);
    }

    /** Blocks self-disable and self-revocation of a directly-held {@code ROLE_ADMIN} via profile update. */
    public boolean canUpdateUser(UUID targetId, boolean enabled, Collection<String> roles) {
        if (!isSelf(targetId)) {
            return true;
        }
        if (!enabled) {
            return false; // would disable own account
        }
        return !userService.hasRole(targetId, ADMIN_ROLE) || containsAdmin(roles); // must keep own admin role
    }

    private boolean isSelf(UUID targetId) {
        return currentUserId().map(id -> id.equals(targetId)).orElse(false);
    }

    private static boolean containsAdmin(Collection<String> roles) {
        return roles != null && roles.contains(ADMIN_ROLE);
    }

    private Optional<UUID> currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        return userService.findByUsername(authentication.getName())
                .map(user -> user.getId());
    }
}
