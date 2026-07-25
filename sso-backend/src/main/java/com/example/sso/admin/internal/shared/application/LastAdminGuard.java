package com.example.sso.admin.internal.shared.application;

import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The actor-independent integrity invariant: every tier must always retain at least one enabled, effective
 * administrator. Enforced as a 409 (not a 403) — it constrains every actor, super admins included — and is
 * shared by the user-, role- and group-admin services, any of which can strip the last administrator.
 *
 * <p>Two tiers. The PLATFORM tier ({@code orgId == null}) must keep one enabled global {@code ROLE_ADMIN}
 * super. Each TENANT tier ({@code orgId} non-null) must keep one enabled holder of its OWN {@code ROLE_ORG_ADMIN}
 * whose effective (deny-applied) authorities still include {@link Permissions#USER_UPDATE} — the capability to
 * appoint/remove admins and manage users ({@code @CanGrantRole}/{@code @CanUpdateUser} both require it). A holder
 * whose admin capability has been denied away no longer keeps the org administrable, so they do not count; a
 * global super is deny-exempt, so it always retains the capability but counts only toward the PLATFORM tier —
 * never a tenant's, so a tenant cannot strip its own last admin merely because a platform super exists.
 *
 * <p>The guard runs AFTER the mutation, inside the mutation's transaction: it takes a per-tier advisory
 * {@code xact} lock so concurrent trigger operations serialize, then recounts the post-state — two operations
 * cannot each treat the other's victim as the surviving admin, a race no DB constraint can express. A tenant
 * that nonetheless loses every admin is recoverable, not bricked: a platform super drills in and re-appoints one
 * (the documented un-brick path — a fail-closed guard needs an administrative undo, see identity-binding.md).
 */
@Component
@RequiredArgsConstructor
public class LastAdminGuard {

    private final RoleService roleService;
    private final UserService userService;
    private final TierAdvisoryLock tierLock;

    /**
     * Rejects (409) an operation that has left {@code orgId}'s tier with no enabled effective administrator.
     * Must be called from inside the mutation's transaction (so the advisory xact-lock and the recount see the
     * same committed state); {@code orgId} null is the platform tier, a non-null org is a tenant.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureTierRetainsAdmin(UUID orgId) {
        tierLock.serialize(orgId); // serialize the tier's whole trigger set BEFORE recounting the post-state
        if (!tierHasEnabledAdmin(orgId)) {
            throw ConflictException.of("admin.lastAdmin");
        }
    }

    private boolean tierHasEnabledAdmin(UUID orgId) {
        return orgId == null ? platformHasEnabledSuper() : orgHasEffectiveAdmin(orgId);
    }

    /** A missing role is nothing to guard (no admin to lose), never a reason to block every mutation. A super is
     *  a global user and deny-exempt, so an enabled global {@code ROLE_ADMIN} holder is admin enough. */
    private boolean platformHasEnabledSuper() {
        return roleService.findByName(Roles.ADMIN)
                .map(role -> sameTierHolders(role.getId(), null).anyMatch(UserAccount::isEnabled))
                .orElse(true);
    }

    private boolean orgHasEffectiveAdmin(UUID orgId) {
        // The org's OWN ROLE_ORG_ADMIN only: findByName falls back to the global template, whose holders belong
        // to no single tenant — counting them would answer the wrong org. A missing own role is nothing to guard.
        return roleService.findByName(Roles.ORG_ADMIN, orgId)
                .filter(role -> orgId.equals(role.getOrgId()))
                .map(role -> sameTierHolders(role.getId(), orgId).anyMatch(this::retainsAdminCapability))
                .orElse(true);
    }

    /** Holders of the role — direct AND group-delegated — confined to the tier's own users. The same-tier
     *  filter is defense in depth: it keeps the count independent of the same-org assignment invariant, so a
     *  foreign holder could never be miscounted as this tier's surviving admin. */
    private Stream<UserAccount> sameTierHolders(UUID roleId, UUID tierOrgId) {
        return roleService.effectiveHolders(roleId).stream()
                .filter(user -> Objects.equals(user.getOrgId(), tierOrgId));
    }

    private boolean retainsAdminCapability(UserAccount user) {
        return user.isEnabled()
                && userService.effectiveAuthorities(user.getId()).contains(Permissions.USER_UPDATE);
    }
}
