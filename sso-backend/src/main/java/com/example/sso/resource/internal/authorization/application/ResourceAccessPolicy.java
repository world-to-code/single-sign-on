package com.example.sso.resource.internal.authorization.application;

import com.example.sso.resource.authorization.ApplicationAuthorization;
import com.example.sso.resource.authorization.GroupAuthorization;
import com.example.sso.resource.authorization.ResourceAuthorization;
import com.example.sso.resource.authorization.UserAuthorization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.shared.error.BadRequestException;
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
    private final OrganizationService organizations;

    /**
     * A platform super-admin: a direct or group-delegated {@code ROLE_ADMIN}. Bypasses everything across
     * ALL tenants. Kept distinct from {@link #isTierAdmin()} because super-only operations (managing the
     * GLOBAL resource-type vocabulary, delegating resource-admin to a user) must NOT open to a tenant admin.
     */
    public boolean isUnscoped() {
        return hasAuthority(Roles.ADMIN);
    }

    /**
     * A tenant tier-admin: a platform super ({@code ROLE_ADMIN}) OR an org admin ({@code ROLE_ORG_ADMIN}).
     * Manages the ENTIRE resource tree of the current org tier; RLS provides the org boundary, so a tenant
     * tier-admin only ever loads/mutates their own org's resources (a foreign resource is a 404). Used for
     * STRUCTURE management (create/rename/delete/edge), NOT for user-referencing delegation.
     */
    public boolean isTierAdmin() {
        return isUnscoped() || hasAuthority(Roles.ORG_ADMIN);
    }

    private boolean hasAuthority(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(role::equals);
    }

    /** Resources this caller administers (empty for a scoped caller with no resolvable actor/grants). */
    public Set<UUID> managedResourceIds() {
        return actorId().map(resourceAuth::managedResourceIds).orElseGet(Set::of);
    }

    /** True if the caller is a tier-admin (own-org, RLS-bounded) OR the resource is in their managed subtree. */
    public boolean canManage(UUID resourceId) {
        return isTierAdmin() || actorId().map(id -> resourceAuth.canManage(id, resourceId)).orElse(false);
    }

    /** Denies (403) unless the caller manages the resource (tier-admin or subtree). */
    public void requireManage(UUID resourceId) {
        if (!canManage(resourceId)) {
            throw new ForbiddenException("Outside your managed resources.");
        }
    }

    /**
     * Grantee eligibility for resource-admin delegation. Delegating references a GLOBAL user id, so on an
     * org-scoped resource the grantee must be a MEMBER of that org — a tenant admin may delegate only to its
     * own org's members, never grant a global outsider admin over the subtree. A platform super delegates
     * freely; a global resource ({@code orgId == null}) has no membership to check. Composes on top of the
     * caller's manage-reach ({@link #requireManage}) + the resource's own tier check, checked by the service.
     */
    public void requireGranteeInOrg(UUID orgId, UUID userId) {
        if (isUnscoped() || orgId == null) {
            return;
        }
        if (!organizations.isMember(orgId, userId)) {
            throw BadRequestException.of("resource.member.notInOrg");
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
        // Resolve the ACTING principal by their own identity, not the org they have drilled into: the platform
        // super-admin is a GLOBAL account (org_id NULL, carrying ROLE_ADMIN), resolved globally so a same-named
        // user planted in the drilled org can't be mistaken for the actor. A tenant admin is in their own org.
        boolean platformAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(Roles.ADMIN::equals);
        return (platformAdmin
                ? users.findByUsernameInOrg(authentication.getName(), null)
                : users.findByUsername(authentication.getName()))
                .map(UserAccount::getId);
    }
}
