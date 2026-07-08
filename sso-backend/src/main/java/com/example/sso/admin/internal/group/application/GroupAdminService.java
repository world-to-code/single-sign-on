package com.example.sso.admin.internal.group.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.portal.ApplicationService;
import com.example.sso.portal.ApplicationView;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
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
    private final OrgContext orgContext;

    public Page<GroupView> list(int page, int size) {
        if (accessPolicy.isCurrentActorUnscoped()) {
            return userGroups.listAll(page, size);                        // platform super-admin: every org
        }
        if (accessPolicy.administersBoundOrg()) {
            // A tenant admin sees ONLY their own org's groups — NOT the GLOBAL/system groups (e.g. the platform
            // "All Users") that RLS keeps visible for login role-resolution but which they cannot manage.
            return userGroups.listByOrg(actingOrg(), page, size);
        }
        return userGroups.listByIds(accessPolicy.currentScopedGroupIds(), page, size); // resource delegate: subtree
    }

    /** The org the acting admin is bound to (their login org, or a drill-in), or null for the platform tier. */
    private UUID actingOrg() {
        return orgContext.currentOrg().orElse(null);
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
        if (accessPolicy.isCurrentActorUnscoped()) {
            return userGroups.search(query, limit);                       // platform super-admin: every org
        }
        if (accessPolicy.administersBoundOrg()) {
            return userGroups.searchInOrg(query, actingOrg(), limit);     // tenant admin: own org only (no globals)
        }

        Set<UUID> scoped = accessPolicy.currentScopedGroupIds();
        return userGroups.search(query, limit).stream()
                .filter(suggestion -> scoped.contains(UUID.fromString(suggestion.id()))).toList();
    }

    private void requireAccess(UUID groupId) {
        if (!accessPolicy.canAccessGroup(groupId)) {
            throw new ForbiddenException("Outside your managed groups.");
        }
    }
}
