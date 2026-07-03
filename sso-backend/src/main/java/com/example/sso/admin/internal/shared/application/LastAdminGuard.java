package com.example.sso.admin.internal.shared.application;

import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The actor-independent platform invariant: at least one enabled administrator must always remain.
 * Enforced as a 409 (not a 403) — it constrains every actor, super admins included — and is shared by
 * the user-admin and role-admin services, which can each strip the last {@code ROLE_ADMIN}.
 */
@Component
@RequiredArgsConstructor
public class LastAdminGuard {

    private static final String ADMIN_ROLE = Roles.ADMIN;

    private final RoleService roleService;

    /**
     * Rejects (409) an operation that would leave the platform with no enabled administrator.
     * {@code remainsEnabledAdmin} is whether the target stays an enabled admin after the operation (then
     * there is nothing to guard).
     */
    public void ensureNotLastAdmin(UUID targetId, boolean remainsEnabledAdmin) {
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
}
