package com.example.sso.admin.internal.group.application;

import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.RequestIds;
import com.example.sso.audit.AuditType;
import com.example.sso.portal.ApplicationService;
import com.example.sso.portal.ApplicationView;
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
 */
@Service
@RequiredArgsConstructor
public class GroupAdminService {

    private final UserGroupService userGroups;
    private final ApplicationService applications;
    private final AdminAuditLogger auditLogger;

    public List<GroupView> list() {
        return userGroups.listAll();
    }

    public GroupView create(GroupRequest request) {
        return userGroups.create(request.toSpec());
    }

    public GroupView update(UUID id, GroupRequest request) {
        return userGroups.update(id, request.toSpec());
    }

    public void delete(UUID id) {
        userGroups.delete(id);
    }

    /** Replaces the roles delegated to a group; members inherit them. */
    public GroupView setRoles(UUID id, Set<String> requestedRoleNames) {
        Set<String> roleNames = Objects.requireNonNullElseGet(requestedRoleNames, Set::of);
        GroupView view = userGroups.setRoles(id, roleNames);
        auditLogger.log(AuditType.GROUP_ROLES_UPDATED, "group=" + id + " roles=" + roleNames);
        return view;
    }

    /** Replaces the group's managers (scoped admins allowed to manage its members). */
    public GroupView setManagers(UUID id, List<String> managerUserIds) {
        GroupView view = userGroups.setManagers(id, RequestIds.toUuidSet(managerUserIds));
        auditLogger.log(AuditType.GROUP_MANAGERS_UPDATED, "group=" + id + " managers=" + managerUserIds);
        return view;
    }

    public GroupView get(UUID id) {
        return userGroups.get(id);
    }

    public GroupMembersPage members(UUID id, int page, int size) {
        return userGroups.members(id, page, size);
    }

    public List<ApplicationView> applications(UUID id) {
        return applications.appsForGroup(id);
    }

    public List<Suggestion> search(String query, int limit) {
        return userGroups.search(query, limit);
    }
}
