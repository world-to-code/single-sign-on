package com.example.sso.admin.internal.group.application;

import com.example.sso.admin.internal.shared.application.ActingAdminTier;
import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.user.application.UserDetailAdminService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.user.group.GroupMembersPage;
import com.example.sso.user.group.GroupRequest;
import com.example.sso.user.group.GroupView;
import com.example.sso.user.account.Suggestion;
import com.example.sso.user.group.UserGroupService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    public GroupView update(UUID id, GroupRequest request) {
        requireAccess(id);
        return userGroups.update(id, request.toSpec());
    }

    public void delete(UUID id) {
        requireAccess(id);
        userGroups.delete(id);
    }

    /** Replaces the roles delegated to a group; members inherit them. */
    public GroupView setRoles(UUID id, Set<String> requestedRoleNames) {
        requireAccess(id);
        Set<String> roleNames = Objects.requireNonNullElseGet(requestedRoleNames, Set::of);
        GroupView view = userGroups.setRoles(id, roleNames);
        auditLogger.log(AuditType.GROUP_ROLES_UPDATED, AuditSubjectType.GROUP, id.toString(),
                "group=" + id + " roles=" + roleNames);
        return view;
    }

    public GroupView get(UUID id) {
        requireAccess(id);
        return userGroups.get(id);
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

    private void requireAccess(UUID groupId) {
        if (!accessPolicy.canAccessGroup(groupId)) {
            throw ForbiddenException.of("admin.group.outsideScope");
        }
    }
}
