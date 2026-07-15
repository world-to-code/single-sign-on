package com.example.sso.admin.internal.shared.application;

import com.example.sso.admin.internal.audit.application.AuditScope;
import com.example.sso.resource.authorization.ApplicationAuthorization;
import com.example.sso.resource.authorization.GroupAuthorization;
import com.example.sso.resource.authorization.ResourceAuthorization;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.organization.OrganizationAuthorization;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.resource.authorization.UserAuthorization;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleHierarchyService;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.account.UserService;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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

    /** The permission catalog — a granted name must be one of these, never a role name or session marker. */
    private static final Set<String> CATALOG = Set.copyOf(Permissions.ALL);

    private final UserService userService;
    private final RoleService roleService;
    private final RoleHierarchyService roleHierarchy;
    private final UserGroupService userGroups;
    private final UserAuthorization userAuth;
    private final GroupAuthorization groupAuth;
    private final ApplicationAuthorization appAuth;
    private final ResourceAuthorization resourceAuth;
    private final OrganizationAuthorization orgAuth;
    private final ApplicationService applications;
    private final OrgContext orgContext;

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
                || userAuth.canManage(actorId, targetId)
                || boundOrgContainsTarget(targetId);
    }

    /**
     * A TENANT (org) admin acting within their own org reaches any user that BELONGS to that org — the
     * organization, not a resource subtree, is their management scope. The target's org is read
     * authoritatively ({@code app_user} carries no RLS), so a foreign-org user is out of scope even though
     * the row is physically readable.
     */
    private boolean boundOrgContainsTarget(UUID targetId) {
        if (!administersBoundOrg()) {
            return false;
        }
        UUID boundOrg = orgContext.currentOrg().orElse(null);
        return boundOrg != null && userService.orgIdOf(targetId).map(boundOrg::equals).orElse(false);
    }

    /**
     * Who may mint new user accounts: a super admin (anywhere), or a TENANT admin acting within their own
     * org (the new user is stamped with that org). The roles a non-super may assign at creation are still
     * gated by {@link #mayAssignRoles} on the endpoint, so a tenant admin cannot create an administrator.
     */
    public boolean canCreateUser() {
        return currentUserId().map(this::isSuper).orElse(false) || administersBoundOrg();
    }

    /**
     * Only a super admin may assign a privileged role (ROLE_ADMIN/ROLE_GROUP_ADMIN/ROLE_ORG_ADMIN) — e.g.
     * by delegating it to a group — OR a role that CARRIES a platform-only permission. Otherwise a non-super
     * admin with group management could grant admin (or a platform permission, via a super-created role that
     * bundles one) to a group they belong to and escalate. A set with no privileged/platform-bearing role is
     * allowed for anyone.
     */
    /**
     * Whether the current actor may make a mapping rule assign the given target — the same authority the manual
     * path demands. GROUP: {@code canAccessGroup}. ROLE: the grant-only-what-you-hold / dominance check resolved
     * BY ID (mirroring {@code canManageRoleMembership}), NOT by name — a by-name detour would let a tenant grant
     * a privileged GLOBAL role by minting a same-named benign org role, since the name resolves org-first while
     * the grant targets the stored id. Fails closed on an unresolved id.
     */
    public boolean mayAssignTarget(MappingTargetKind kind, UUID targetId) {
        return switch (kind) {
            case GROUP -> canAccessGroup(targetId);
            case ROLE -> currentIsSuperAdmin()
                    || (currentActorMayManageRole(targetId)
                            && !roleCarriesPlatformPermission(targetId)
                            && actorHoldsAllPermissionsOf(targetId));
        };
    }

    public boolean mayAssignRoles(Collection<String> roleNames) {
        if (currentIsSuperAdmin()) {
            return true;
        }
        if (roleNames == null) {
            return true;
        }
        // A non-super may assign a role only if it is NOT strictly above them in the inheritance DAG (at or
        // below their level — so a tenant ORG_ADMIN can hand out ORG_ADMIN/GROUP_ADMIN/USER within their
        // tenant, but nobody can hand out ROLE_ADMIN), carries no platform-only permission, and they
        // themselves hold every permission it carries (grant-only-what-you-hold — the real escalation floor).
        // All three compose with AND, and each fails closed on an unknown/unresolved name.
        return roleNames.stream().allMatch(name ->
                currentActorMayManageRoleName(name)
                        && !roleCarriesPlatformPermission(name)
                        && actorHoldsAllPermissionsOfRole(name));
    }

    /**
     * Whether the acting admin holds {@code ROLE_ADMIN} DIRECTLY — gates super-only privileged grants
     * (role/permission assignment); visibility scoping uses {@link #isCurrentActorUnscoped()} instead.
     */
    public boolean currentIsSuperAdmin() {
        return currentUserId().map(this::isSuper).orElse(false);
    }

    /**
     * The acting admin's APEX roles in the inheritance DAG — where a role they create must be attached so it
     * sits strictly beneath them (and is therefore one they may assign). Empty for an unresolved actor.
     */
    public Set<UUID> currentActorApexRoleIds() {
        return currentUserId().map(roleHierarchy::apexRolesOf).orElse(Set.of());
    }

    /**
     * Whether the acting admin may manage the role (by id) — i.e. it is NOT strictly above them in the DAG
     * (at or below their level). Fail-closed on an unresolved actor. Escalation is still blocked by the
     * grant-only-what-you-hold and platform-permission guards that compose with this on the endpoints.
     */
    public boolean currentActorMayManageRole(UUID roleId) {
        return currentUserId().map(actorId -> roleHierarchy.actorMayManageRole(actorId, roleId)).orElse(false);
    }

    /**
     * Whether the acting admin may manage the role NAMED here — resolved in the acting tier, org-first — i.e.
     * it is not strictly above them. Fail-closed on an unresolved actor or unknown name.
     */
    public boolean currentActorMayManageRoleName(String roleName) {
        return currentUserId()
                .map(actorId -> roleHierarchy.actorMayManageRoleName(actorId, roleName, actingOrg()))
                .orElse(false);
    }

    /** The roles that strictly OUTRANK the acting admin — hidden from their role listing (empty for super). */
    public Set<UUID> currentRolesAboveActor() {
        return currentUserId().map(roleHierarchy::rolesAboveActor).orElse(Set.of());
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

    /**
     * Group scope: whether the acting admin may manage {@code groupId}. A super admin bypasses; a resource
     * delegate reaches groups in its subtree; a TENANT (org) admin reaches a group when it is
     * owned by an org they administer. A global (org_id null) group has no administering org, so a tenant admin
     * is denied — which is what keeps them from mutating a platform-wide group even though RLS lets them read it.
     */
    public boolean canAccessGroup(UUID groupId) {
        return currentUserId().map(actorId -> groupAuth.canManage(actorId, groupId)
                || userGroups.orgIdOf(groupId).map(orgId -> orgAuth.canManage(actorId, orgId)).orElse(false)
        ).orElse(false);
    }

    /**
     * Whether the acting admin administers the org they are currently bound to (their login org, or one they
     * drilled into) — i.e. an org admin acting within their own tenant, NOT a mere resource delegate.
     * Such an actor sees their whole org's directory (RLS scopes it) rather than only a resource subtree.
     */
    public boolean administersBoundOrg() {
        return currentUserId().flatMap(actorId ->
                orgContext.currentOrg().map(orgId -> orgAuth.canManage(actorId, orgId))).orElse(false);
    }

    /**
     * The tenant the acting admin is bound to (their login org, or one a super-admin drilled into), or null
     * for the platform tier (an un-drilled super-admin). Tier-scoped reads pass this as the {@code orgId} so a
     * tenant admin sees only their org and an un-drilled super-admin sees only global (org-less) data.
     */
    public UUID actingOrg() {
        return orgContext.currentOrg().orElse(null);
    }

    /** For a scoped acting admin, the ids of the groups inside their resource subtree. */
    public Set<UUID> currentScopedGroupIds() {
        return currentUserId().map(groupAuth::scopedGroupIds).orElse(Set.of());
    }

    /** Org scope: whether the acting admin may administer {@code orgId} (super bypasses; else org-admin+member). */
    public boolean canAccessOrg(UUID orgId) {
        return currentUserId()
                .map(actorId -> resourceAuth.isUnscoped(actorId) || orgAuth.canManage(actorId, orgId))
                .orElse(false);
    }

    /** For a scoped acting admin, the ids of the organizations they administer (their memberships). */
    public Set<UUID> currentScopedOrgIds() {
        return currentUserId().map(orgAuth::scopedOrgIds).orElse(Set.of());
    }

    /**
     * Application scope: whether the acting admin may manage {@code appId}. A super admin bypasses; a resource
     * delegate reaches apps in its subtree; a TENANT (org) admin reaches an app that BELONGS to their bound org
     * — resolved via the tier-scoped catalog ({@code applications.listApplications()} returns only the acting
     * tier's apps), so an app in another tenant (or a global one) is never in their catalog and stays out.
     */
    public boolean canAccessApp(String appId) {
        Optional<UUID> actor = currentUserId();
        if (actor.isEmpty()) {
            return false;
        }
        UUID actorId = actor.get();
        return resourceAuth.isUnscoped(actorId)
                || appAuth.canManage(actorId, appId)
                || (administersBoundOrg() && applications.listApplications().stream()
                        .map(ApplicationView::id).anyMatch(appId::equals));
    }

    /** For a scoped acting admin, the ids of the applications inside their resource subtree. */
    public Set<String> currentScopedAppIds() {
        return currentUserId().map(appAuth::scopedAppIds).orElse(Set.of());
    }

    /**
     * The acting admin's audit visibility WITHIN the already tier-scoped event set (the query has bounded it
     * to the acting org — a tenant's events, or the platform's global events). Unscoped — sees every entry in
     * that tier — for a super admin OR a tenant admin acting in their own org; a resource delegate is narrowed
     * further to the union of their scoped user/group/app/resource ids via {@link AuditScope#permits}.
     */
    public AuditScope currentAuditScope() {
        Optional<UUID> actor = currentUserId();
        if (actor.isEmpty()) {
            return new AuditScope(false, currentUsername(), Set.of(), Set.of(), Set.of(), Set.of());
        }

        UUID actorId = actor.get();
        if (resourceAuth.isUnscoped(actorId) || administersBoundOrg()) {
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

    /**
     * Admin force-expiry of a user's sessions. A super admin may revoke anyone (force-expiring a
     * compromised administrator's sessions is a legitimate response); a scoped delegate may not target
     * another administrator, so they cannot grief a super admin who falls within their user scope.
     */
    public boolean canRevokeSessions(UUID targetId) {
        return currentIsSuperAdmin() || !isAdmin(targetId);
    }

    /** Blocks resetting another administrator's MFA (resetting your own is allowed). */
    public boolean canResetMfa(UUID targetId) {
        return isSelf(targetId) || !isAdmin(targetId);
    }

    /**
     * Who may set a user's DIRECT permissions: a super admin (never another administrator's), or a TENANT
     * admin acting within their own org — a tenant owns its own directory. A resource delegate (scoped, but
     * administering no org) stays blocked outright.
     *
     * <p>This says nothing about WHICH permissions may be handed out: that is {@link #mayGrantPermissions},
     * which blocks platform-only permissions and enforces grant-only-what-you-hold. Both compose on the
     * endpoint, so a tenant admin can never mint an authority they do not themselves hold.
     */
    public boolean canManagePermissions(UUID targetId) {
        if (currentIsSuperAdmin()) {
            return isSelf(targetId) || !isAdmin(targetId);
        }
        return administersBoundOrg() && !isAdmin(targetId);
    }

    /**
     * Grant-only-what-you-hold for DIRECT permission assignment: a non-super may hand out only names that are
     * CATALOG permissions, are tenant-grantable, and that they themselves currently hold — otherwise a tenant
     * admin could grant a puppet account (or themselves) an authority they lack and escalate within the tenant.
     *
     * <p>The catalog check is explicit and NOT delegated to the service: {@link #currentAuthorities()} mixes
     * permissions with role names and session markers ({@code MFA_COMPLETE}, {@code FACTOR_*}), so a bare
     * "the actor holds this string" test would pass for a marker. The service rejects a non-catalog name too;
     * this gate must be correct on its own.
     */
    public boolean mayGrantPermissions(Collection<String> permissions) {
        if (currentIsSuperAdmin()) {
            return true;
        }
        if (permissions == null) {
            return true;
        }
        return CATALOG.containsAll(permissions)
                && permissions.stream().noneMatch(Permissions::isPlatform)
                && currentAuthorities().containsAll(permissions);
    }

    /**
     * Blocks disabling any administrator (self or other) via a profile update, and self-revocation of a
     * directly-held {@code ROLE_ADMIN}. Editing another administrator's roles IS allowed, so a rogue
     * admin can still be demoted (removing {@code ROLE_ADMIN}) — otherwise admins would be permanent.
     */
    public boolean canUpdateUser(UUID targetId, boolean enabled, Collection<String> roles) {
        boolean targetIsAdmin = isAdmin(targetId);
        if (!currentIsSuperAdmin() && (targetIsAdmin || !mayAssignRoles(roles))) {
            // A scoped admin may not touch an administrator account, nor assign — via an UPDATE — a privileged
            // role, a role carrying a platform-only permission, or any role bearing a permission they do not
            // themselves hold. Delegating to mayAssignRoles closes the create→update escalation path (the
            // create gate already runs the same check), not just the narrower privileged-role case.
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
        return canManageRoleMembership(userId, roleId);
    }

    /**
     * Whether the acting admin may REVOKE role {@code roleId} from user {@code userId}. Same scope/privilege
     * rules as granting, plus an admin may not revoke their OWN {@code ROLE_ADMIN} (self-demotion — another
     * admin must). The actor-independent "last administrator" invariant is a 409 in {@link UserAdminService}.
     */
    public boolean canRevokeRole(UUID userId, UUID roleId) {
        if (isSelf(userId) && ADMIN_ROLE.equals(roleName(roleId))) {
            return false;
        }
        return canManageRoleMembership(userId, roleId);
    }

    private boolean canManageRoleMembership(UUID userId, UUID roleId) {
        if (!currentIsSuperAdmin()
                && (isAdmin(userId) || !currentActorMayManageRole(roleId)
                        || roleCarriesPlatformPermission(roleId)
                        || !actorHoldsAllPermissionsOf(roleId))) {
            // A scoped/tenant admin may never touch an admin account, grant a role strictly ABOVE them (e.g.
            // ROLE_ADMIN), grant a role carrying a platform-only permission, nor grant a role carrying any
            // permission they do not themselves hold — all of which would escalate the target or themselves.
            // A role at or below their level IS grantable (a tenant admin manages their whole tier); this
            // applies identically whether the role is granted to a user or delegated to a group.
            return false;
        }
        return canAccessUser(userId);
    }

    /** Whether the role (by id) carries any platform-only permission — un-grantable by a non-super admin. */
    private boolean roleCarriesPlatformPermission(UUID roleId) {
        return roleService.permissionNames(roleId).stream().anyMatch(Permissions::isPlatform);
    }

    /**
     * Whether the role (by name) carries any platform-only permission. Resolved IN THE ACTING TIER (the
     * org's own role of that name first, else the global one) — exactly as the assignment resolves it, so
     * the role that is checked is the role that gets assigned. An unknown name carries nothing.
     */
    private boolean roleCarriesPlatformPermission(String roleName) {
        return roleService.findByName(roleName, actingOrg())
                .map(role -> roleCarriesPlatformPermission(role.getId()))
                .orElse(false);
    }

    /**
     * Grant-only-what-you-hold: whether the acting admin holds EVERY permission the role (by id) carries. A
     * non-super admin may not hand out an authority they lack — otherwise a tenant admin could assign a
     * super-created role bearing a permission they don't have (e.g. {@code user:read}) and escalate. A super
     * admin holds the whole catalog, so this is a no-op for them (and privileged-role gates short-circuit first).
     */
    private boolean actorHoldsAllPermissionsOf(UUID roleId) {
        return currentAuthorities().containsAll(roleService.permissionNames(roleId));
    }

    /**
     * Grant-only-what-you-hold by role name, resolved IN THE ACTING TIER (org's own role first, else the
     * global one) — the same resolution the assignment performs. Fails CLOSED on an unknown name: a role
     * the check cannot see must never be assignable (an org-only name resolved as "unknown" while the
     * service happily assigned the org role was a real escalation path).
     */
    private boolean actorHoldsAllPermissionsOfRole(String roleName) {
        return roleService.findByName(roleName, actingOrg())
                .map(role -> actorHoldsAllPermissionsOf(role.getId()))
                .orElse(false);
    }

    /** The authority strings the acting admin currently holds (role names, permissions, and session markers). */
    private Set<String> currentAuthorities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
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

    private Optional<UUID> currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        // Resolve the ACTING principal by their own identity, not the organization they have drilled into: the
        // platform super-admin is a GLOBAL account (org_id NULL, carrying ROLE_ADMIN), so resolve them globally
        // — otherwise a same-named user planted in the drilled org (allowed by per-org uniqueness) would be
        // mistaken for the actor. A tenant admin is only ever in their own (bound) org.
        boolean platformAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(Roles.ADMIN::equals);
        return (platformAdmin
                ? userService.findByUsernameInOrg(authentication.getName(), null)
                : userService.findByUsername(authentication.getName()))
                .map(UserAccount::getId);
    }
}
