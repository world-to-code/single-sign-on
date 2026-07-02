package com.example.sso.resource.internal.application;

import com.example.sso.resource.ApplicationAuthorization;
import com.example.sso.resource.GroupAuthorization;
import com.example.sso.resource.ResourceAuthorization;
import com.example.sso.resource.UserAuthorization;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Subtree-scope (ABAC) decisions for the resource admin API. A super admin (a direct/group-delegated
 * {@code ROLE_ADMIN}) is UNSCOPED and bypasses every check. A delegated resource admin (an ADMIN grant,
 * no {@code ROLE_ADMIN}) is confined to the resources they administer plus that subtree's descendants.
 *
 * <p>Fails closed: a non-super caller whose principal does not resolve to a user has an EMPTY scope —
 * reads see nothing, mutations are forbidden. The super-admin bypass reads the {@code ROLE_ADMIN}
 * authority from the security context (the effective, group-expanded authority set), not a fresh DB
 * lookup. Composes on top of {@code @RequirePermission} (PBAC), which the controller enforces first.
 */
@Component
@RequiredArgsConstructor
public class ResourceAccessPolicy {

    private final UserService users;
    private final ResourceAuthorization resourceAuth;
    private final GroupAuthorization groupAuth;
    private final ApplicationAuthorization appAuth;
    private final UserAuthorization userAuth;

    /** A direct or group-delegated {@code ROLE_ADMIN} sees and does everything. */
    public boolean isUnscoped() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(Roles.ADMIN::equals);
    }

    /** Resources this caller administers (empty for a scoped caller with no resolvable actor/grants). */
    public Set<UUID> managedResourceIds() {
        return actorId().map(resourceAuth::managedResourceIds).orElseGet(Set::of);
    }

    /** True if the caller is unscoped OR the resource is within their managed subtree. */
    public boolean canManage(UUID resourceId) {
        return isUnscoped() || actorId().map(id -> resourceAuth.canManage(id, resourceId)).orElse(false);
    }

    /** Denies (403) unless the caller manages the resource. */
    public void requireManage(UUID resourceId) {
        if (!canManage(resourceId)) {
            throw new ForbiddenException("Outside your managed resources.");
        }
    }

    /**
     * Pull-in guard: to attach a leaf member the caller must ALREADY manage it (else a scoped admin
     * could absorb an unmanaged group/app/user into their resource to gain visibility over it).
     */
    public void requireManagesMember(MemberType memberType, String memberId) {
        if (isUnscoped()) {
            return;
        }
        UUID actor = actorId().orElseThrow(() -> new ForbiddenException("Outside your managed resources."));
        boolean manages = switch (memberType) {
            case GROUP -> groupAuth.canManage(actor, MemberIds.requireUuid(memberId));
            case USER -> userAuth.canManage(actor, MemberIds.requireUuid(memberId));
            case APPLICATION -> appAuth.canManage(actor, memberId);
            case RESOURCE -> false;
        };
        if (!manages) {
            throw new ForbiddenException("You may only attach a member you already manage.");
        }
    }

    /**
     * The acting user's id. Throws {@link UnauthorizedException} when there is no authenticated caller
     * (401), and returns empty when an authenticated principal does not resolve to a user (fail-closed:
     * empty scope). The two miss modes intentionally differ.
     */
    private Optional<UUID> actorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException();
        }
        return users.findByUsername(authentication.getName()).map(UserAccount::getId);
    }
}
