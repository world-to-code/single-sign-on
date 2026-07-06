package com.example.sso.admin.internal.group.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.portal.ApplicationService;
import com.example.sso.portal.ApplicationView;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.user.GroupMembersPage;
import com.example.sso.user.GroupRequest;
import com.example.sso.user.GroupView;
import com.example.sso.user.Suggestion;
import com.example.sso.user.UserGroupService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class GroupAdminService {

    private final UserGroupService userGroups;
    private final ApplicationService applications;
    private final AdminAccessPolicy accessPolicy;
    private final AdminAuditLogger auditLogger;

    public Page<GroupView> list(int page, int size) {
        // A super admin, or a tenant admin acting within their own org, sees the whole directory RLS returns
        // (all orgs for a super; the bound org's groups for a tenant admin). A mere resource delegate is limited
        // to the groups inside their subtree.
        return accessPolicy.isCurrentActorUnscoped() || accessPolicy.administersBoundOrg()
                ? userGroups.listAll(page, size)
                : userGroups.listByIds(accessPolicy.currentScopedGroupIds(), page, size);
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

    public List<ApplicationView> applications(UUID id) {
        requireAccess(id);
        return applications.appsForGroup(id);
    }

    public List<Suggestion> search(String query, int limit) {
        List<Suggestion> results = userGroups.search(query, limit);
        // The search itself runs under RLS, so a super admin and a tenant admin in their own org already get a
        // correctly-scoped result set; only a resource delegate is further narrowed to its subtree.
        if (accessPolicy.isCurrentActorUnscoped() || accessPolicy.administersBoundOrg()) {
            return results;
        }

        Set<UUID> scoped = accessPolicy.currentScopedGroupIds();
        return results.stream().filter(suggestion -> scoped.contains(UUID.fromString(suggestion.id()))).toList();
    }

    private void requireAccess(UUID groupId) {
        if (!accessPolicy.canAccessGroup(groupId)) {
            throw new ForbiddenException("Outside your managed groups.");
        }
    }
}
