package com.example.sso.admin.internal.shared.application;

import com.example.sso.user.deny.LastAdminInvariant;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Admin's implementation of the {@code user} module's {@link LastAdminInvariant} port: the last-admin invariant
 * is an admin-tier concern ({@link LastAdminGuard}), but the deny WRITE lives in the user module, so it reaches
 * the guard through this port — keeping the guard in admin (where its sibling triggers are) with no user→admin
 * cycle, exactly like {@code DenyAuthorityAdapter}.
 */
@Component
@RequiredArgsConstructor
class LastAdminInvariantAdapter implements LastAdminInvariant {

    private final LastAdminGuard lastAdminGuard;
    private final RoleService roles;

    @Override
    public void ensureDenyRetainsAdmins(String pattern, Collection<UUID> orgIds) {
        lastAdminGuard.ensureDenyRetainsAdmins(pattern, orgIds);
    }

    @Override
    public void ensureRetractionRetainsAdmin(Collection<UUID> retractedRoleIds, UUID orgId) {
        // Only an admin-bearing role can break the invariant. Skipping the rest is not an optimisation: the
        // recount answers "does this tier have an admin NOW", so running it after an unrelated retraction
        // refuses the write for a state that was already true before it.
        if (retractedRoleIds.stream().anyMatch(this::confersAdmin)) {
            lastAdminGuard.ensureTierRetainsAdmin(orgId);
        }
    }

    private boolean confersAdmin(UUID roleId) {
        return roles.findById(roleId)
                .map(role -> Roles.ADMIN.equals(role.getName()) || Roles.ORG_ADMIN.equals(role.getName()))
                .orElse(false);
    }
}
