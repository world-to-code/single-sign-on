package com.example.sso.admin.internal.shared.application;

import com.example.sso.organization.OrganizationAuthorization;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.resource.authorization.ApplicationAuthorization;
import com.example.sso.resource.authorization.GroupAuthorization;
import com.example.sso.resource.authorization.ResourceAuthorization;
import com.example.sso.resource.authorization.UserAuthorization;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserService;
import com.example.sso.user.group.UserGroupService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * REACH: whether the acting administrator may touch this object at all — before any question of what they may
 * do to it. Three shapes of actor, and the order they are asked in matters.
 *
 * <ul>
 *   <li>A super admin is UNSCOPED and bypasses everything.</li>
 *   <li>A resource delegate reaches what their subtree covers, through the resource module's ports.</li>
 *   <li>A TENANT admin reaches whatever BELONGS to the org they are bound to — the organization, not a
 *       subtree, is their management scope.</li>
 * </ul>
 *
 * <p>The tenant branch is the one that needs care: membership is read authoritatively rather than trusted to
 * RLS, because a row being physically readable is not the same as being in scope. A global (org-less) group or
 * app has no administering org, which is exactly what keeps a tenant admin from mutating platform-wide objects
 * they can nonetheless see.
 *
 * <p>Every method fails CLOSED on an unresolved actor.
 */
@Component
@RequiredArgsConstructor
class AdminScope {

    private final ActingAdmin actingAdmin;
    private final UserService userService;
    private final UserGroupService userGroups;
    private final UserAuthorization userAuth;
    private final GroupAuthorization groupAuth;
    private final ApplicationAuthorization appAuth;
    private final ResourceAuthorization resourceAuth;
    private final OrganizationAuthorization orgAuth;
    private final ApplicationService applications;
    private final OrgContext orgContext;

    /** A super acts on anyone; a scoped admin on themselves, their subtree, and their own org's users. */
    boolean canAccessUser(UUID targetId) {
        Optional<UUID> actor = actingAdmin.id();
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
     * Who may mint new accounts: a super (anywhere), or a tenant admin within their own org (the new user is
     * stamped with it). Which ROLES they may then assign is a separate ceiling, so this does not let a tenant
     * admin create an administrator.
     */
    boolean canCreateUser() {
        return actingAdmin.id().map(actingAdmin::isSuper).orElse(false) || administersBoundOrg();
    }

    /** List filtering must branch on this FIRST: the scoped/managed sets below are empty for a super. */
    boolean isCurrentActorUnscoped() {
        return actingAdmin.id().map(resourceAuth::isUnscoped).orElse(false);
    }

    Set<UUID> currentManagedUserIds() {
        return actingAdmin.id().map(userAuth::scopedUserIds).orElse(Set.of());
    }

    boolean canAccessGroup(UUID groupId) {
        return actingAdmin.id().map(actorId -> canAccessGroup(actorId, groupId)).orElse(false);
    }

    /** For an EXPLICIT actor — evaluated off the request thread by the mapping author re-validation. */
    boolean canAccessGroup(UUID actorId, UUID groupId) {
        return groupAuth.canManage(actorId, groupId)
                || userGroups.orgIdOf(groupId).map(orgId -> orgAuth.canManage(actorId, orgId)).orElse(false);
    }

    /**
     * Whether the actor administers the org they are BOUND to (their login org, or one they drilled into) —
     * an org admin acting in their own tenant, not a mere resource delegate. Such an actor sees the whole
     * directory of that org rather than a subtree.
     */
    boolean administersBoundOrg() {
        return actingAdmin.id().flatMap(actorId ->
                orgContext.currentOrg().map(orgId -> orgAuth.canManage(actorId, orgId))).orElse(false);
    }

    /** The bound tenant, or null for the platform tier (an un-drilled super). Tier-scoped reads pass this. */
    UUID actingOrg() {
        return orgContext.currentOrg().orElse(null);
    }

    Set<UUID> currentScopedGroupIds() {
        return actingAdmin.id().map(groupAuth::scopedGroupIds).orElse(Set.of());
    }

    boolean canAccessOrg(UUID orgId) {
        return actingAdmin.id()
                .map(actorId -> resourceAuth.isUnscoped(actorId) || orgAuth.canManage(actorId, orgId))
                .orElse(false);
    }

    Set<UUID> currentScopedOrgIds() {
        return actingAdmin.id().map(orgAuth::scopedOrgIds).orElse(Set.of());
    }

    /**
     * The tenant branch here resolves through the tier-scoped catalog: {@code listApplications} returns only
     * the acting tier's apps, so another tenant's app — or a global one — is never in the list and stays out.
     */
    boolean canAccessApp(String appId) {
        Optional<UUID> actor = actingAdmin.id();
        if (actor.isEmpty()) {
            return false;
        }
        UUID actorId = actor.get();
        return resourceAuth.isUnscoped(actorId)
                || appAuth.canManage(actorId, appId)
                || (administersBoundOrg() && applications.listApplications().stream()
                        .map(ApplicationView::id).anyMatch(appId::equals));
    }

    Set<String> currentScopedAppIds() {
        return actingAdmin.id().map(appAuth::scopedAppIds).orElse(Set.of());
    }

    /** The scoped sets an audit or list view narrows by, for an actor that is not unscoped. */
    Set<UUID> scopedGroupIdsOf(UUID actorId) {
        return groupAuth.scopedGroupIds(actorId);
    }

    Set<String> scopedAppIdsOf(UUID actorId) {
        return appAuth.scopedAppIds(actorId);
    }

    Set<UUID> managedResourceIdsOf(UUID actorId) {
        return resourceAuth.managedResourceIds(actorId);
    }

    boolean isUnscoped(UUID actorId) {
        return resourceAuth.isUnscoped(actorId);
    }

    /** Reach for a RESOURCE, by id — a mapping rule's resource-membership target is judged on this alone. */
    boolean canManageResource(UUID actorId, UUID resourceId) {
        return resourceAuth.canManage(actorId, resourceId);
    }

    /**
     * A tenant admin reaches any user that BELONGS to their bound org. The target's org is read
     * authoritatively ({@code app_user} carries no RLS), so a foreign-org user is out of scope even though the
     * row is physically readable.
     */
    private boolean boundOrgContainsTarget(UUID targetId) {
        if (!administersBoundOrg()) {
            return false;
        }
        UUID boundOrg = orgContext.currentOrg().orElse(null);
        return boundOrg != null && userService.orgIdOf(targetId).map(boundOrg::equals).orElse(false);
    }
}
