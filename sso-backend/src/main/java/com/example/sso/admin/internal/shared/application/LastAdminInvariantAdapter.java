package com.example.sso.admin.internal.shared.application;

import com.example.sso.mapping.LastAdminRetractionGuard;
import com.example.sso.user.deny.LastAdminInvariant;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleService;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Admin's implementation of the {@code user} module's {@link LastAdminInvariant} port: the last-admin invariant
 * is an admin-tier concern ({@link LastAdminGuard}), but the deny WRITE lives in the user module, so it reaches
 * the guard through this port — keeping the guard in admin (where its sibling triggers are) with no user→admin
 * cycle, exactly like {@code DenyAuthorityAdapter}.
 *
 * <p>It implements TWO ports because two modules ask two different questions of the same guard: the deny write
 * lives in {@code user}, the ABAC retraction in {@code mapping}. Each port is declared by its own caller, so
 * neither module has to see the other's contract.
 */
@Component
@RequiredArgsConstructor
class LastAdminInvariantAdapter implements LastAdminInvariant, LastAdminRetractionGuard {

    private final LastAdminGuard lastAdminGuard;
    private final RoleService roles;

    @Override
    public void ensureDenyRetainsAdmins(String pattern, Collection<UUID> orgIds) {
        lastAdminGuard.ensureDenyRetainsAdmins(pattern, orgIds);
    }

    @Override
    public boolean retractionWouldNeedGuarding(Collection<UUID> roleIds, UUID orgId) {
        // Cheap half first: the tier read below walks every admin-role holder's effective authorities, and
        // the overwhelming majority of retractions cannot touch the capability at all.
        return confersAdminCapability(roleIds) && lastAdminGuard.tierHasAdmin(orgId);
    }

    @Override
    public void ensureRetractionRetainsAdmin(UUID orgId) {
        lastAdminGuard.ensureTierRetainsAdmin(orgId);
    }

    /**
     * By CAPABILITY, not by role name. {@code ROLE_ORG_ADMIN}'s permission set is editable, so an org may
     * legitimately move {@code user:update} onto a separate role — and the recount turns on {@code user:update}
     * alone ({@code LastAdminGuard.retainsAdminCapability}). Comparing names therefore skipped the recount for
     * exactly the role that mattered, and let the tier lose its last effective admin with no 409. Effective
     * permissions, not the role's own, so a role that inherits the capability down the DAG counts too.
     */
    private boolean confersAdminCapability(Collection<UUID> roleIds) {
        return !roleIds.isEmpty() && roles.effectivePermissionNames(roleIds).contains(Permissions.USER_UPDATE);
    }
}
