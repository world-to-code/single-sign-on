package com.example.sso.admin.internal.group.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.RequestIds;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.portal.ApplicationService;
import com.example.sso.portal.ApplicationView;
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

    public List<GroupView> list() {
        List<GroupView> all = userGroups.listAll();
        if (accessPolicy.isCurrentActorUnscoped()) {
            return all;
        }

        Set<UUID> scoped = accessPolicy.currentScopedGroupIds();
        return all.stream().filter(group -> scoped.contains(UUID.fromString(group.id()))).toList();
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

    /** Replaces the group's managers (scoped admins allowed to manage its members). */
    public GroupView setManagers(UUID id, List<String> managerUserIds) {
        requireAccess(id);
        GroupView view = userGroups.setManagers(id, RequestIds.toUuidSet(managerUserIds));
        auditLogger.log(AuditType.GROUP_MANAGERS_UPDATED, AuditSubjectType.GROUP, id.toString(),
                "group=" + id + " managers=" + managerUserIds);
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
        if (accessPolicy.isCurrentActorUnscoped()) {
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
