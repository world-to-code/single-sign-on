package com.example.sso.admin.internal.group.application;

import com.example.sso.admin.internal.shared.application.ActingAdminTier;
import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.LastAdminGuard;
import com.example.sso.admin.internal.shared.application.MembershipDenyCeiling;
import com.example.sso.admin.internal.user.application.UserDetailAdminService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.user.group.GroupMembersPage;
import com.example.sso.user.group.GroupRequest;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.group.GroupView;
import com.example.sso.user.account.Suggestion;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.role.RoleService;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Presentation-facing adapter for the group admin API: delegates to {@link UserGroupService} (each
 * request maps itself to the domain command) and audits the delegation changes, keeping the controller
 * a thin delegator.
 *
 * <p>Enforces subtree scope imperatively: {@code list} filters to the actor's scoped groups and every
 * by-id method calls {@link #requireAccess} (super admin bypasses). {@code create} stays unscoped — a
 * fresh group confers no reach.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GroupAdminService {

    private final UserGroupService userGroups;
    private final ApplicationService applications;
    private final AdminAccessPolicy accessPolicy;
    private final AdminAuditLogger auditLogger;
    private final UserDetailAdminService userDetail;
    private final ActingAdminTier tier;
    private final LastAdminGuard lastAdminGuard;
    private final RoleService roleService;
    private final DenyService denyService;
    private final MembershipDenyCeiling membershipDenies;

    public Page<GroupView> list(int page, int size) {
        // Tier-scoped: an un-drilled platform admin (tier null) sees ONLY the global/system groups; a super-admin
        // drilled into a tenant, or a tenant admin, sees THAT org's groups — never all tenants' groups merged.
        if (tier.administersWholeTier()) {
            return userGroups.listByOrg(tier.actingOrg(), page, size);
        }
        return userGroups.listByIds(accessPolicy.currentScopedGroupIds(), page, size); // resource delegate: subtree
    }

    public GroupView create(GroupRequest request) {
        return userGroups.create(request.toSpec());
    }

    /**
     * A full replace: {@code memberUserIds} omitted means the empty set, so this route can empty a group
     * without ever naming a member. That makes it a membership DROP, and it answers to the lift ceiling for
     * the same reason {@code DELETE /roles/{id}/members/{userId}} does — see {@link MembershipDenyCeiling}.
     */
    @Transactional
    public GroupView update(UUID id, GroupRequest request) {
        requireAccess(id);
        GroupSpec spec = request.toSpec();
        requireMayDropMembersOf(id, spec.memberIds());
        return userGroups.update(id, spec);
    }

    /** Deleting the group drops every membership AND the deny riding on the group itself — always a lift act. */
    @Transactional
    public void delete(UUID id) {
        requireAccess(id);
        membershipDenies.requireMayDropGroup(id);
        userGroups.delete(id);
    }

    /** Refused only when the replace actually loses somebody: a rename must not need the lift authority. */
    private void requireMayDropMembersOf(UUID groupId, Set<UUID> desired) {
        if (userGroups.replaceWouldDropMembers(groupId, desired)) {
            membershipDenies.requireMayDropGroup(groupId);
        }
    }

    /** Replaces the roles delegated to a group; members inherit them. Transactional so the admin-invariant
     *  recount runs in the mutation's tx: dropping a group's {@code ROLE_ORG_ADMIN} delegation strips the
     *  admin capability from its group-delegated admins, which could brick the group's org. */
    @Transactional
    public GroupView setRoles(UUID id, Set<UUID> requestedRoleIds) {
        requireAccess(id);
        Set<UUID> roleIds = Objects.requireNonNullElseGet(requestedRoleIds, Set::of);
        requireMayChange(id, roleIds);
        GroupView view = userGroups.setRoles(id, roleIds);
        lastAdminGuard.ensureTierRetainsAdmin(userGroups.orgIdOf(id).orElse(null));
        // The NAMES in the trail, from the view the write returned: an audit line of uuids is unreadable, and
        // the names are now a rendering of what was bound rather than what was asked for.
        auditLogger.log(AuditType.GROUP_ROLES_UPDATED, AuditSubjectType.GROUP, id.toString(),
                "group=" + id + " roles=" + view.roleNames());
        return view;
    }

    public GroupView get(UUID id) {
        requireAccess(id);
        return userGroups.get(id);
    }

    /** The group's deny-management state: the permissions its delegated roles hand to members (the candidates an
     *  admin may withhold) and the denies already on the group. Server-authoritative authz stays in the deny
     *  service; the read is tier-scoped by {@link #requireAccess} + the deny table's RLS. */
    @Transactional(readOnly = true)
    public GroupDenyView denies(UUID id) {
        requireAccess(id);
        Set<UUID> roleIds = userGroups.delegatedRoleIds(Set.of(id)).getOrDefault(id, Set.of());
        List<String> candidates = roleService.effectivePermissionNames(roleIds).stream().sorted().toList();
        return new GroupDenyView(candidates, denyService.principalDenies(DenySubjectKind.GROUP, id));
    }

    public GroupMembersPage members(UUID id, int page, int size) {
        requireAccess(id);
        return userGroups.members(id, page, size);
    }

    /**
     * Ends the live sessions of ALL the group's members in one action (e.g. off-boarding a team), reusing the
     * per-user terminator so each member's OIDC/SAML participants are logged out via the same
     * {@code SessionDestroyedEvent} path. A member the caller may not revoke (an administrator, for a scoped
     * delegate) is SKIPPED, never escalated. Org-scoped: {@code memberIdsOf} and {@code terminateSessions} act
     * only within the acting tenant.
     */
    public GroupSessionTermination terminateMemberSessions(UUID id) {
        requireAccess(id);
        int users = 0;
        int sessions = 0;
        int skipped = 0;
        for (UUID memberId : userGroups.memberIdsOf(List.of(id))) {
            // Apply the SAME per-member reach the single-user force-expiry composes (canAccessUser AND
            // canRevokeSessions): a scoped delegate must not reach a same-org member OUTSIDE its subtree, nor
            // force-logout an administrator, merely by virtue of shared group membership.
            if (!accessPolicy.canAccessUser(memberId) || !accessPolicy.canRevokeSessions(memberId)) {
                skipped++;
                continue;
            }
            try {
                sessions += userDetail.terminateSessions(memberId);
                users++;
            } catch (RuntimeException e) {
                // A member vanished mid-batch (stale membership row): isolate it so one bad member never aborts
                // the whole off-boarding, leaving the rest still logged in.
                skipped++;
                log.warn("skipping group member {} during bulk session termination", memberId, e);
            }
        }
        auditLogger.log(AuditType.SESSION_ADMIN_REVOKED, AuditSubjectType.GROUP, id.toString(),
                "group members signed out: users=" + users + " sessions=" + sessions + " skipped=" + skipped);
        return new GroupSessionTermination(users, sessions, skipped);
    }

    public List<ApplicationView> applications(UUID id) {
        requireAccess(id);
        return applications.appsForGroup(id);
    }

    public List<Suggestion> search(String query, int limit) {
        if (tier.administersWholeTier()) {
            return userGroups.searchInOrg(query, tier.actingOrg(), limit);     // tier-scoped (global groups if null)
        }

        Set<UUID> scoped = accessPolicy.currentScopedGroupIds();
        return userGroups.search(query, limit).stream()
                .filter(suggestion -> scoped.contains(UUID.fromString(suggestion.id()))).toList();
    }

    /**
     * The ceiling applies to what is REMOVED as well as what is added.
     *
     * <p>This is a full replace, and the endpoint's gate can only see the request — so an empty list passed it
     * without resolving the actor at all, and a replace that only removed was authorized by nothing. Stripping
     * a role from a group demotes every member, and accounts that held it through the group stop being
     * protected by the other-administrator guards, which read effective authorities. Taking away a privilege
     * one could not have granted is the same act in reverse.
     *
     * <p>It lives here rather than in the annotation because the CURRENT delegations are only knowable once the
     * group is resolved, which the annotation cannot do.
     */
    private void requireMayChange(UUID groupId, Set<UUID> desired) {
        Set<UUID> delegated = userGroups.delegatedRoleIds(Set.of(groupId)).getOrDefault(groupId, Set.of());
        Set<UUID> changing = new HashSet<>(desired);
        changing.addAll(delegated);
        if (!accessPolicy.mayAssignRoleIds(changing)) {
            throw ForbiddenException.of("admin.group.roleOutsideCeiling");
        }
        // The grant ceiling above asks whether the actor could have CONFERRED these roles. Undelegating one is
        // the other polarity and has its own authority: a deny resolves against the holder's apex roles, so
        // dropping the delegation drops the deny that rode on it for every member at once.
        membershipDenies.requireMayDropRoles(
                delegated.stream().filter(roleId -> !desired.contains(roleId)).toList());
    }

    private void requireAccess(UUID groupId) {
        if (!accessPolicy.canAccessGroup(groupId)) {
            throw ForbiddenException.of("admin.group.outsideScope");
        }
    }
}
